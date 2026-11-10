package io.deltaflow.saga.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.builder.SagaStepDefinition;
import io.deltaflow.saga.domain.SagaExecutionContext;
import io.deltaflow.saga.domain.SagaState;
import io.deltaflow.saga.domain.SagaStepState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SagaCoordinator.class);

    private final SagaJournalStore journalStore;
    private final ObjectMapper objectMapper;

    public sealed interface SagaEvent permits
            SagaEvent.StartExecution,
            SagaEvent.CompleteSaga,
            SagaEvent.FailSaga,
            SagaEvent.StartCompensation {
        record StartExecution(int stepIndex, String stepName) implements SagaEvent {}
        record CompleteSaga(Map<String, Object> result) implements SagaEvent {}
        record FailSaga(String reason) implements SagaEvent {}
        record StartCompensation(String cause) implements SagaEvent {}
    }

    public sealed interface StepEvent permits
            StepEvent.Start,
            StepEvent.Complete,
            StepEvent.Fail,
            StepEvent.Compensate {
        record Start(Map<String, Object> requestPayload) implements StepEvent {}
        record Complete(Map<String, Object> responsePayload) implements StepEvent {}
        record Fail(String errorDetails) implements StepEvent {}
        record Compensate() implements StepEvent {}
    }

    public SagaCoordinator(SagaJournalStore journalStore, ObjectMapper objectMapper) {
        this.journalStore = journalStore;
        this.objectMapper = objectMapper;
    }

    public <I, R> R executeSaga(
            SagaDefinition<I, R> definition,
            UUID sagaId,
            String tenantId,
            String idempotencyKey,
            I input
    ) {
        Objects.requireNonNull(definition, "Saga definition must not be null");
        Objects.requireNonNull(sagaId, "SagaId must not be null");
        tenantId = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;

        String inputJson = serialize(input);
        journalStore.createSagaInstance(sagaId, tenantId, idempotencyKey, definition.getName(), inputJson);

        SagaExecutionContext context = new SagaExecutionContext(sagaId, tenantId, idempotencyKey);
        List<SagaStepDefinition<I>> steps = definition.getSteps();

        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            SagaStepDefinition<I> step = steps.get(stepIndex);
            log.info("Saga [{}]: Persisting WAL intent for step {} [{}]", sagaId, stepIndex, step.getName());

            // Write-Ahead Log: Persist intent BEFORE outbound execution
            journalStore.recordStepIntent(sagaId, stepIndex, step.getName(), inputJson);

            Object stepResult;
            try {
                stepResult = step.getAction().apply(context, input);
            } catch (Exception e) {
                log.error("Saga [{}] failed at step {} [{}]: {}", sagaId, stepIndex, step.getName(), e.getMessage());
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                journalStore.recordStepFailure(sagaId, stepIndex, errorMsg);
                journalStore.failSaga(sagaId, "Step " + stepIndex + " (" + step.getName() + ") failed: " + errorMsg);
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Saga step execution failed: " + step.getName(), e);
            }

            String stepResponsePayload = serialize(stepResult);
            journalStore.recordStepCompletion(sagaId, stepIndex, stepResponsePayload);
            context = context.withStepResult(step.getName(), stepResult);
            log.info("Saga [{}]: Completed step {} [{}]", sagaId, stepIndex, step.getName());
        }

        R finalResult = definition.getCompletionHandler() != null
                ? definition.getCompletionHandler().apply(context, input)
                : null;
        String finalResultJson = serialize(finalResult);
        journalStore.completeSaga(sagaId, finalResultJson);
        log.info("Saga [{}] completed successfully", sagaId);

        return finalResult;
    }

    public SagaState transitionSagaState(SagaState currentState, SagaEvent event) {
        return switch (currentState) {
            case SagaState.Pending p -> switch (event) {
                case SagaEvent.StartExecution e -> new SagaState.Executing(p.sagaId(), p.tenantId(), 0, e.stepName(), Instant.now());
                case SagaEvent.FailSaga f -> new SagaState.Failed(p.sagaId(), p.tenantId(), 0, f.reason(), Instant.now());
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(p.sagaId(), p.tenantId(), c.result(), Instant.now());
                case SagaEvent.StartCompensation sc -> new SagaState.Compensating(p.sagaId(), p.tenantId(), 0, sc.cause());
            };
            case SagaState.Executing ex -> switch (event) {
                case SagaEvent.StartExecution e -> new SagaState.Executing(ex.sagaId(), ex.tenantId(), e.stepIndex(), e.stepName(), Instant.now());
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(ex.sagaId(), ex.tenantId(), c.result(), Instant.now());
                case SagaEvent.FailSaga f -> new SagaState.Failed(ex.sagaId(), ex.tenantId(), ex.currentStepIndex(), f.reason(), Instant.now());
                case SagaEvent.StartCompensation sc -> new SagaState.Compensating(ex.sagaId(), ex.tenantId(), ex.currentStepIndex(), sc.cause());
            };
            case SagaState.Completed c -> c;
            case SagaState.Failed f -> f;
            case SagaState.Compensating cmp -> switch (event) {
                case SagaEvent.FailSaga f -> new SagaState.Failed(cmp.sagaId(), cmp.tenantId(), cmp.rollbackStepIndex(), f.reason(), Instant.now());
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(cmp.sagaId(), cmp.tenantId(), c.result(), Instant.now());
                case SagaEvent.StartExecution e -> cmp;
                case SagaEvent.StartCompensation sc -> cmp;
            };
        };
    }

    public SagaStepState transitionStepState(SagaStepState currentState, StepEvent event) {
        return switch (currentState) {
            case SagaStepState.NotStarted ns -> switch (event) {
                case StepEvent.Start ex -> new SagaStepState.Executing(ns.stepId(), ns.stepIndex(), ns.stepName(), ex.requestPayload(), Instant.now());
                case StepEvent.Fail fl -> new SagaStepState.Failed(ns.stepId(), ns.stepIndex(), ns.stepName(), fl.errorDetails(), Instant.now());
                case StepEvent.Complete cp -> new SagaStepState.Completed(ns.stepId(), ns.stepIndex(), ns.stepName(), cp.responsePayload(), Instant.now());
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(ns.stepId(), ns.stepIndex(), ns.stepName(), Instant.now());
            };
            case SagaStepState.Executing ex -> switch (event) {
                case StepEvent.Complete cp -> new SagaStepState.Completed(ex.stepId(), ex.stepIndex(), ex.stepName(), cp.responsePayload(), Instant.now());
                case StepEvent.Fail fl -> new SagaStepState.Failed(ex.stepId(), ex.stepIndex(), ex.stepName(), fl.errorDetails(), Instant.now());
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(ex.stepId(), ex.stepIndex(), ex.stepName(), Instant.now());
                case StepEvent.Start s -> ex;
            };
            case SagaStepState.Completed cp -> switch (event) {
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(cp.stepId(), cp.stepIndex(), cp.stepName(), Instant.now());
                case StepEvent.Complete c -> cp;
                case StepEvent.Fail f -> cp;
                case StepEvent.Start s -> cp;
            };
            case SagaStepState.Failed fl -> fl;
            case SagaStepState.Compensated cm -> cm;
        };
    }

    private String serialize(Object object) {
        if (object == null) {
            return null;
        }
        if (object instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return "{\"value\":\"" + object.toString() + "\"}";
        }
    }
}

