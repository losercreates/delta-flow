package io.deltaflow.saga.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.saga.builder.ParallelBranchDefinition;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.builder.SagaStepDefinition;
import io.deltaflow.saga.domain.SagaExecutionContext;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaState;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.domain.SagaStepState;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Supplier;

@Service
public class SagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SagaCoordinator.class);

    private final SagaJournalStore journalStore;
    private final ObjectMapper objectMapper;
    private final io.deltaflow.outbox.service.OutboxService outboxService;
    private final io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry;
    private final io.deltaflow.dashboard.service.DashboardEventBroadcaster eventBroadcaster;
    private final io.deltaflow.observability.tracing.DeltaFlowTracer tracer;
    private final io.deltaflow.observability.health.GracefulShutdownHandler shutdownHandler;
    private final io.deltaflow.observability.metrics.DeltaFlowMetrics metrics;
    private final io.deltaflow.idempotency.lock.DistributedLockManager lockManager;
    private final Map<String, SagaDefinition<?, ?>> sagaDefinitions = new java.util.concurrent.ConcurrentHashMap<>();

    public sealed interface SagaEvent permits
            SagaEvent.StartExecution,
            SagaEvent.CompleteSaga,
            SagaEvent.FailSaga,
            SagaEvent.StartCompensation,
            SagaEvent.CompleteCompensation {
        record StartExecution(int stepIndex, String stepName) implements SagaEvent {}
        record CompleteSaga(Map<String, Object> result) implements SagaEvent {}
        record FailSaga(String reason) implements SagaEvent {}
        record StartCompensation(String cause) implements SagaEvent {}
        record CompleteCompensation(Map<String, Object> compensationResult) implements SagaEvent {}
    }

    public sealed interface StepEvent permits
            StepEvent.Start,
            StepEvent.Complete,
            StepEvent.Fail,
            StepEvent.StartCompensation,
            StepEvent.Compensate {
        record Start(Map<String, Object> requestPayload) implements StepEvent {}
        record Complete(Map<String, Object> responsePayload) implements StepEvent {}
        record Fail(String errorDetails) implements StepEvent {}
        record StartCompensation() implements StepEvent {}
        record Compensate() implements StepEvent {}
    }

    @Autowired
    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService,
            io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry,
            @Autowired(required = false) io.deltaflow.dashboard.service.DashboardEventBroadcaster eventBroadcaster,
            @Autowired(required = false) io.deltaflow.observability.tracing.DeltaFlowTracer tracer,
            @Autowired(required = false) io.deltaflow.observability.health.GracefulShutdownHandler shutdownHandler,
            @Autowired(required = false) io.deltaflow.observability.metrics.DeltaFlowMetrics metrics,
            @Autowired(required = false) io.deltaflow.idempotency.lock.DistributedLockManager lockManager,
            @Autowired(required = false) List<SagaDefinition<?, ?>> definitions
    ) {
        this.journalStore = journalStore;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
        this.resiliencePolicyRegistry = resiliencePolicyRegistry;
        this.eventBroadcaster = eventBroadcaster;
        this.tracer = tracer;
        this.shutdownHandler = shutdownHandler;
        this.metrics = metrics;
        this.lockManager = lockManager;
        if (definitions != null) {
            for (SagaDefinition<?, ?> def : definitions) {
                this.sagaDefinitions.put(def.getName(), def);
            }
        }
    }

    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService,
            io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry,
            @Autowired(required = false) io.deltaflow.dashboard.service.DashboardEventBroadcaster eventBroadcaster,
            @Autowired(required = false) io.deltaflow.observability.tracing.DeltaFlowTracer tracer,
            @Autowired(required = false) io.deltaflow.observability.health.GracefulShutdownHandler shutdownHandler,
            @Autowired(required = false) io.deltaflow.observability.metrics.DeltaFlowMetrics metrics,
            @Autowired(required = false) io.deltaflow.idempotency.lock.DistributedLockManager lockManager
    ) {
        this(journalStore, objectMapper, outboxService, resiliencePolicyRegistry, eventBroadcaster, tracer, shutdownHandler, metrics, lockManager, null);
    }

    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService,
            io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry,
            io.deltaflow.dashboard.service.DashboardEventBroadcaster eventBroadcaster,
            io.deltaflow.observability.tracing.DeltaFlowTracer tracer,
            io.deltaflow.observability.health.GracefulShutdownHandler shutdownHandler,
            io.deltaflow.observability.metrics.DeltaFlowMetrics metrics
    ) {
        this(journalStore, objectMapper, outboxService, resiliencePolicyRegistry, eventBroadcaster, tracer, shutdownHandler, metrics, null);
    }

    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService,
            io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry,
            io.deltaflow.dashboard.service.DashboardEventBroadcaster eventBroadcaster,
            io.deltaflow.observability.tracing.DeltaFlowTracer tracer,
            io.deltaflow.observability.health.GracefulShutdownHandler shutdownHandler
    ) {
        this(journalStore, objectMapper, outboxService, resiliencePolicyRegistry, eventBroadcaster, tracer, shutdownHandler, null);
    }

    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService,
            io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry,
            io.deltaflow.dashboard.service.DashboardEventBroadcaster eventBroadcaster,
            io.deltaflow.observability.tracing.DeltaFlowTracer tracer
    ) {
        this(journalStore, objectMapper, outboxService, resiliencePolicyRegistry, eventBroadcaster, tracer, null);
    }

    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService,
            io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry,
            io.deltaflow.dashboard.service.DashboardEventBroadcaster eventBroadcaster
    ) {
        this(journalStore, objectMapper, outboxService, resiliencePolicyRegistry, eventBroadcaster, null);
    }

    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService,
            io.deltaflow.resilience.ResiliencePolicyRegistry resiliencePolicyRegistry
    ) {
        this(journalStore, objectMapper, outboxService, resiliencePolicyRegistry, null);
    }

    public SagaCoordinator(
            SagaJournalStore journalStore,
            ObjectMapper objectMapper,
            io.deltaflow.outbox.service.OutboxService outboxService
    ) {
        this(journalStore, objectMapper, outboxService, null);
    }

    public SagaCoordinator(SagaJournalStore journalStore, ObjectMapper objectMapper) {
        this(journalStore, objectMapper, null, null);
    }

    public void registerDefinition(SagaDefinition<?, ?> definition) {
        if (definition != null) {
            this.sagaDefinitions.put(definition.getName(), definition);
        }
    }

    public Optional<SagaDefinition<?, ?>> getDefinition(String sagaType) {
        return Optional.ofNullable(sagaDefinitions.get(sagaType));
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

        if (shutdownHandler != null) {
            shutdownHandler.registerSagaStart();
        }
        Instant sagaStartTime = Instant.now();
        try {
            String inputJson = serialize(input);
            journalStore.createSagaInstance(sagaId, tenantId, idempotencyKey, definition.getName(), inputJson);
            if (outboxService != null) {
                outboxService.recordEvent("SAGA_INSTANCE", sagaId.toString(), "SAGA_STARTED", inputJson, tenantId, idempotencyKey);
            }
            if (eventBroadcaster != null) {
                eventBroadcaster.broadcast("SAGA_STARTED", sagaId, tenantId, Map.of(
                        "sagaType", definition.getName(),
                        "status", "EXECUTING",
                        "idempotencyKey", idempotencyKey
                ));
            }

            SagaExecutionContext context = new SagaExecutionContext(sagaId, tenantId, idempotencyKey);
            return executeForwardSteps(definition, sagaId, tenantId, idempotencyKey, input, inputJson, context, 0, sagaStartTime);
        } finally {
            if (shutdownHandler != null) {
                shutdownHandler.registerSagaEnd();
            }
        }
    }

    public Object resumeSaga(
            UUID sagaId,
            String tenantId,
            String eventName,
            Object eventPayload
    ) {
        SagaInstance instance = journalStore.findSaga(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("Saga instance not found: " + sagaId));
        String sagaType = instance.getSagaType();
        SagaDefinition<?, ?> definition = sagaDefinitions.get(sagaType);
        if (definition == null) {
            throw new IllegalStateException("No registered SagaDefinition found for sagaType: " + sagaType);
        }
        return resumeSaga((SagaDefinition<Object, Object>) definition, sagaId, tenantId, eventName, eventPayload);
    }

    public <I, R> R resumeSaga(
            SagaDefinition<I, R> definition,
            UUID sagaId,
            String tenantId,
            String eventName,
            Object eventPayload
    ) {
        Objects.requireNonNull(definition, "Saga definition must not be null");
        Objects.requireNonNull(sagaId, "SagaId must not be null");

        SagaInstance instance = journalStore.findSaga(sagaId)
                .orElseThrow(() -> new IllegalArgumentException("Saga instance not found: " + sagaId));

        String effectiveTenantId = (tenantId == null || tenantId.isBlank()) ? instance.getTenantId() : tenantId;
        String idempotencyKey = instance.getIdempotencyKey();

        if ("COMPLETED".equals(instance.getStatus())) {
            log.info("Saga [{}] is already COMPLETED, returning cached result", sagaId);
            if (instance.getResultPayload() != null) {
                try {
                    return (R) objectMapper.readValue(instance.getResultPayload(), Object.class);
                } catch (Exception e) {
                    log.warn("Failed to deserialize completed saga result: {}", e.getMessage());
                }
            }
            return null;
        }

        if ("TIMED_OUT".equals(instance.getStatus()) || "COMPENSATING".equals(instance.getStatus())
                || "COMPENSATED".equals(instance.getStatus()) || "FAILED".equals(instance.getStatus())) {
            log.warn("Zombie webhook rejected for saga [{}] in terminal/compensating state [{}]", sagaId, instance.getStatus());
            throw new IllegalStateException("ZOMBIE_WEBHOOK_REJECTED: Saga " + sagaId + " is in terminal/compensating state " + instance.getStatus());
        }

        if (!"SUSPENDED".equals(instance.getStatus())) {
            throw new IllegalStateException("Saga " + sagaId + " is not SUSPENDED; current status: " + instance.getStatus());
        }

        if (instance.getAwaitingEventName() != null && !instance.getAwaitingEventName().equals(eventName)) {
            throw new IllegalArgumentException("Saga is awaiting event [" + instance.getAwaitingEventName() + "] but received [" + eventName + "]");
        }

        String lockToken = null;
        if (lockManager != null) {
            Optional<String> tokenOpt = lockManager.acquireLock(effectiveTenantId, idempotencyKey, java.time.Duration.ofSeconds(30));
            if (tokenOpt.isPresent()) {
                lockToken = tokenOpt.get();
            } else {
                log.warn("Could not acquire distributed lock for saga resumption: tenant={}, key={}", effectiveTenantId, idempotencyKey);
            }
        }

        if (shutdownHandler != null) {
            shutdownHandler.registerSagaStart();
        }
        Instant sagaStartTime = Instant.now();

        try {
            int suspendedIndex = instance.getCurrentStep();

            // Check if webhook payload indicates rejection
            boolean isRejection = false;
            String rejectionReason = null;
            if (eventPayload instanceof Map<?, ?> map) {
                Object statusObj = map.get("status");
                if (statusObj != null) {
                    String s = statusObj.toString();
                    if ("REJECTED".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s)) {
                        isRejection = true;
                        rejectionReason = "External event status: " + s;
                    }
                }
            } else if (eventPayload instanceof String jsonStr && (jsonStr.startsWith("{") || jsonStr.startsWith("["))) {
                try {
                    Map<?, ?> map = objectMapper.readValue(jsonStr, Map.class);
                    Object statusObj = map.get("status");
                    if (statusObj != null) {
                        String s = statusObj.toString();
                        if ("REJECTED".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s)) {
                            isRejection = true;
                            rejectionReason = "External event status: " + s;
                        }
                    }
                } catch (Exception ignored) {}
            }

            I input = null;
            if (instance.getPayload() != null) {
                try {
                    if (definition.getInputType() != null) {
                        input = objectMapper.readValue(instance.getPayload(), definition.getInputType());
                    } else {
                        input = (I) objectMapper.readValue(instance.getPayload(), Object.class);
                    }
                } catch (Exception e) {
                    log.warn("Failed to deserialize input payload for saga [{}]: {}", sagaId, e.getMessage());
                }
            }

            SagaExecutionContext context = new SagaExecutionContext(sagaId, effectiveTenantId, idempotencyKey);
            List<SagaStep> recordedSteps = journalStore.getStepsForSaga(sagaId);
            for (SagaStep s : recordedSteps) {
                if ("COMPLETED".equals(s.getStatus()) && s.getResponsePayload() != null) {
                    try {
                        Object respObj = objectMapper.readValue(s.getResponsePayload(), Object.class);
                        context = context.withStepResult(s.getStepName(), respObj);
                    } catch (Exception ignored) {}
                }
            }

            if (isRejection) {
                log.warn("Saga [{}]: Webhook payload indicates rejection at step {} [{}]: {}", sagaId, suspendedIndex, eventName, rejectionReason);
                journalStore.recordStepFailure(sagaId, suspendedIndex, rejectionReason);
                if (eventBroadcaster != null) {
                    eventBroadcaster.broadcast("STEP_STATE_CHANGED", sagaId, effectiveTenantId, Map.of(
                            "stepIndex", suspendedIndex,
                            "eventName", eventName,
                            "status", "FAILED",
                            "error", rejectionReason
                    ));
                }
                compensateSaga(definition, sagaId, effectiveTenantId, idempotencyKey, input, context, suspendedIndex, rejectionReason);
                throw new io.deltaflow.saga.exception.SagaCompensatedException(
                        sagaId,
                        idempotencyKey,
                        eventName,
                        rejectionReason,
                        Collections.emptyList(),
                        null
                );
            }

            // Normal resumption
            SagaStep suspendedStep = recordedSteps.stream()
                    .filter(s -> s.getStepIndex() == suspendedIndex)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Suspended step not found for saga: " + sagaId + " at index: " + suspendedIndex));

            validateStepFencing(sagaId, suspendedIndex, suspendedStep.getFencingToken());

            String payloadJson = serialize(eventPayload);
            journalStore.recordStepCompletion(sagaId, suspendedIndex, payloadJson);
            journalStore.recordSagaResumption(sagaId);

            if (eventBroadcaster != null) {
                eventBroadcaster.broadcast("SAGA_RESUMED", sagaId, effectiveTenantId, Map.of(
                        "stepIndex", suspendedIndex,
                        "eventName", eventName,
                        "status", "EXECUTING"
                ));
            }
            if (outboxService != null) {
                outboxService.recordEvent("SAGA_INSTANCE", sagaId.toString(), "SAGA_RESUMED", payloadJson, effectiveTenantId, idempotencyKey);
            }

            context = context.withStepResult(suspendedStep.getStepName(), eventPayload);

            return executeForwardSteps(definition, sagaId, effectiveTenantId, idempotencyKey, input, instance.getPayload(), context, suspendedIndex + 1, sagaStartTime);
        } finally {
            if (lockManager != null && lockToken != null) {
                lockManager.releaseLock(effectiveTenantId, idempotencyKey, lockToken);
            }
            if (shutdownHandler != null) {
                shutdownHandler.registerSagaEnd();
            }
        }
    }

    private <I, R> R executeForwardSteps(
            SagaDefinition<I, R> definition,
            UUID sagaId,
            String tenantId,
            String idempotencyKey,
            I input,
            String inputJson,
            SagaExecutionContext initialContext,
            int startingStepIndex,
            Instant sagaStartTime
    ) {
        SagaExecutionContext context = initialContext;
        List<SagaStepDefinition<I>> steps = definition.getSteps();

        for (int stepIndex = startingStepIndex; stepIndex < steps.size(); stepIndex++) {
            SagaStepDefinition<I> step = steps.get(stepIndex);

            if (step.isAsyncWait()) {
                log.info("Saga [{}]: Step {} [{}] is an asynchronous wait for event [{}]",
                        sagaId, stepIndex, step.getName(), step.getAwaitingEventName());

                SagaStep suspendedStep = journalStore.recordStepSuspension(
                        sagaId,
                        stepIndex,
                        step.getName(),
                        step.getAwaitingEventName(),
                        step.getTimeout()
                );

                if (eventBroadcaster != null) {
                    eventBroadcaster.broadcast("SAGA_SUSPENDED", sagaId, tenantId, Map.of(
                            "stepIndex", stepIndex,
                            "stepName", step.getName(),
                            "status", "SUSPENDED",
                            "awaitingEvent", step.getAwaitingEventName(),
                            "stepDeadline", suspendedStep.getStepDeadline() != null ? suspendedStep.getStepDeadline().toString() : ""
                    ));
                }

                if (outboxService != null) {
                    outboxService.recordEvent("SAGA_INSTANCE", sagaId.toString(), "SAGA_SUSPENDED",
                            "{\"step\":\"" + step.getName() + "\",\"awaitingEvent\":\"" + step.getAwaitingEventName() + "\"}",
                            tenantId, idempotencyKey);
                }

                if (lockManager != null) {
                    lockManager.releaseLock(tenantId, idempotencyKey, null);
                }

                throw new io.deltaflow.saga.exception.SagaSuspendedException(
                        sagaId,
                        tenantId,
                        idempotencyKey,
                        stepIndex,
                        step.getName(),
                        step.getAwaitingEventName(),
                        suspendedStep.getStepDeadline()
                );
            }

            log.info("Saga [{}]: Persisting WAL intent for step {} [{}]", sagaId, stepIndex, step.getName());

            // Write-Ahead Log: Persist intent BEFORE outbound execution
            SagaStep recordedStep = journalStore.recordStepIntent(sagaId, stepIndex, step.getName(), inputJson);
            if (metrics != null) {
                metrics.incrementActiveSteps();
            }
            if (eventBroadcaster != null) {
                eventBroadcaster.broadcast("STEP_STATE_CHANGED", sagaId, tenantId, Map.of(
                        "stepIndex", stepIndex,
                        "stepName", step.getName(),
                        "status", "EXECUTING"
                ));
            }

            io.opentelemetry.api.trace.Span stepSpan = (tracer != null)
                    ? tracer.startStepSpan(sagaId, stepIndex, step.getName())
                    : null;

            Object stepResult;
            try {
                if (step.isParallel()) {
                    stepResult = executeParallelStep(step, context, input, sagaId, stepIndex);
                } else {
                    stepResult = executeStepAction(step, context, input);
                }
                if (tracer != null) {
                    tracer.recordStepSuccess(stepSpan);
                }
            } catch (CallNotPermittedException e) {
                if (metrics != null) {
                    metrics.decrementActiveSteps();
                    metrics.recordStepTransition(step.getName(), "FAILED");
                    metrics.recordCompensation(definition.getName());
                    metrics.recordSagaDuration(definition.getName(), "FAILED", java.time.Duration.between(sagaStartTime, Instant.now()));
                }
                if (tracer != null) {
                    tracer.recordStepFailure(stepSpan, e);
                }
                log.warn("Saga [{}] step {} [{}] fast-failed due to open circuit breaker: {}",
                        sagaId, stepIndex, step.getName(), e.getMessage());
                String errorMsg = "Circuit breaker open: " + e.getMessage();
                journalStore.recordStepFailure(sagaId, stepIndex, errorMsg);
                if (eventBroadcaster != null) {
                    eventBroadcaster.broadcast("STEP_STATE_CHANGED", sagaId, tenantId, Map.of(
                            "stepIndex", stepIndex,
                            "stepName", step.getName(),
                            "status", "FAILED",
                            "error", errorMsg
                    ));
                }
                compensateSaga(definition, sagaId, tenantId, idempotencyKey, input, context, stepIndex, errorMsg, e);
                throw e;
            } catch (Exception e) {
                if (metrics != null) {
                    metrics.decrementActiveSteps();
                    metrics.recordStepTransition(step.getName(), "FAILED");
                    metrics.recordCompensation(definition.getName());
                    metrics.recordSagaDuration(definition.getName(), "COMPENSATED", java.time.Duration.between(sagaStartTime, Instant.now()));
                }
                if (tracer != null) {
                    tracer.recordStepFailure(stepSpan, e);
                }
                log.error("Saga [{}] failed at step {} [{}]: {}", sagaId, stepIndex, step.getName(), e.getMessage());
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                journalStore.recordStepFailure(sagaId, stepIndex, errorMsg);
                if (eventBroadcaster != null) {
                    eventBroadcaster.broadcast("STEP_STATE_CHANGED", sagaId, tenantId, Map.of(
                            "stepIndex", stepIndex,
                            "stepName", step.getName(),
                            "status", "FAILED",
                            "error", errorMsg
                    ));
                }

                // Backward compensation for all previously completed steps in reverse order
                compensateSaga(definition, sagaId, tenantId, idempotencyKey, input, context, stepIndex, errorMsg, e);

                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Saga step execution failed: " + step.getName(), e);
            }

            // Zombie protection: validate that the step has not been superseded or cancelled
            validateStepFencing(sagaId, stepIndex, recordedStep.getFencingToken());

            String stepResponsePayload = serialize(stepResult);
            journalStore.recordStepCompletion(sagaId, stepIndex, stepResponsePayload);
            if (metrics != null) {
                metrics.decrementActiveSteps();
                metrics.recordStepTransition(step.getName(), "COMPLETED");
            }
            if (outboxService != null) {
                outboxService.recordEvent("SAGA_STEP", sagaId + ":" + stepIndex, "STEP_COMPLETED", stepResponsePayload, tenantId, idempotencyKey);
            }
            if (eventBroadcaster != null) {
                eventBroadcaster.broadcast("STEP_STATE_CHANGED", sagaId, tenantId, Map.of(
                        "stepIndex", stepIndex,
                        "stepName", step.getName(),
                        "status", "COMPLETED"
                ));
            }
            context = context.withStepResult(step.getName(), stepResult);
            log.info("Saga [{}]: Completed step {} [{}]", sagaId, stepIndex, step.getName());
        }

        R finalResult = definition.getCompletionHandler() != null
                ? definition.getCompletionHandler().apply(context, input)
                : null;
        String finalResultJson = serialize(finalResult);
        journalStore.completeSaga(sagaId, finalResultJson);
        if (outboxService != null) {
            outboxService.recordEvent("SAGA_INSTANCE", sagaId.toString(), "SAGA_COMPLETED", finalResultJson, tenantId, idempotencyKey);
        }
        if (eventBroadcaster != null) {
            eventBroadcaster.broadcast("SAGA_COMPLETED", sagaId, tenantId, Map.of(
                    "status", "COMPLETED"
            ));
        }
        if (metrics != null) {
            metrics.recordSagaDuration(definition.getName(), "COMPLETED", java.time.Duration.between(sagaStartTime, Instant.now()));
        }
        log.info("Saga [{}] completed successfully", sagaId);

        return finalResult;
    }

    private <I> Object executeStepAction(SagaStepDefinition<I> step, SagaExecutionContext context, I input) {
        Supplier<Object> supplier = () -> step.getAction().apply(context, input);
        if (resiliencePolicyRegistry != null &&
                (step.getCircuitBreakerName() != null || step.getRetryName() != null)) {
            supplier = resiliencePolicyRegistry.decorateSupplier(
                    step.getCircuitBreakerName(),
                    step.getRetryName(),
                    supplier
            );
        }
        return supplier.get();
    }

    private <I> Map<String, Object> executeParallelStep(
            SagaStepDefinition<I> step,
            SagaExecutionContext context,
            I input,
            UUID sagaId,
            int stepIndex
    ) throws Exception {
        List<ParallelBranchDefinition<I>> branches = step.getParallelBranches();
        Map<String, StructuredTaskScope.Subtask<Object>> branchSubtasks = new LinkedHashMap<>();
        Map<String, Object> branchOutputs = new LinkedHashMap<>();

        try (var scope = new StructuredTaskScope.ShutdownOnFailure("saga-" + sagaId + "-step-" + stepIndex,
                Thread.ofVirtual().name("saga-" + sagaId + "-branch-", 0).factory())) {

            var parentTrace = io.deltaflow.observability.tracing.TraceContextHolder.get();
            for (ParallelBranchDefinition<I> branch : branches) {
                log.info("Saga [{}]: Forking parallel branch [{}] on Virtual Thread", sagaId, branch.getBranchName());
                StructuredTaskScope.Subtask<Object> subtask = scope.fork(() -> {
                    if (parentTrace != null) {
                        io.deltaflow.observability.tracing.TraceContextHolder.set(parentTrace);
                    }
                    try {
                        return branch.getAction().apply(context, input);
                    } finally {
                        if (parentTrace != null) {
                            io.deltaflow.observability.tracing.TraceContextHolder.clear();
                        }
                    }
                });
                branchSubtasks.put(branch.getBranchName(), subtask);
            }

            scope.join();

            // Check if failure occurred
            if (scope.exception().isPresent()) {
                Throwable failure = scope.exception().get();
                log.error("Saga [{}]: Parallel step [{}] encountered branch failure: {}",
                        sagaId, step.getName(), failure.getMessage());

                // Compensate any successfully completed branches within this parallel step in reverse order
                for (int b = branches.size() - 1; b >= 0; b--) {
                    ParallelBranchDefinition<I> branch = branches.get(b);
                    StructuredTaskScope.Subtask<Object> subtask = branchSubtasks.get(branch.getBranchName());
                    if (subtask != null && subtask.state() == StructuredTaskScope.Subtask.State.SUCCESS) {
                        try {
                            log.info("Saga [{}]: Compensating completed parallel branch [{}]", sagaId, branch.getBranchName());
                            branch.getCompensation().accept(context, input);
                        } catch (Exception compEx) {
                            log.error("Saga [{}]: Branch compensation failed for [{}]: {}",
                                    sagaId, branch.getBranchName(), compEx.getMessage());
                        }
                    }
                }

                if (failure instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException("Parallel branch failed", failure);
            }

            // All succeeded
            for (Map.Entry<String, StructuredTaskScope.Subtask<Object>> entry : branchSubtasks.entrySet()) {
                branchOutputs.put(entry.getKey(), entry.getValue().get());
            }
        }

        return branchOutputs;
    }

    public <I, R> void compensateSaga(
            SagaDefinition<I, R> definition,
            UUID sagaId,
            String tenantId,
            String idempotencyKey,
            I input,
            SagaExecutionContext context,
            int failedStepIndex,
            String failureReason
    ) {
        compensateSaga(definition, sagaId, tenantId, idempotencyKey, input, context, failedStepIndex, failureReason, null);
    }

    public <I, R> void compensateSaga(
            SagaDefinition<I, R> definition,
            UUID sagaId,
            String tenantId,
            String idempotencyKey,
            I input,
            SagaExecutionContext context,
            int failedStepIndex,
            String failureReason,
            Throwable cause
    ) {
        log.warn("Saga [{}]: Initiating backward compensation from failed step {} ({})", sagaId, failedStepIndex, failureReason);
        journalStore.startSagaCompensation(sagaId, failedStepIndex, failureReason);

        List<SagaStepDefinition<I>> steps = definition.getSteps();
        List<String> compensatedStepNames = new ArrayList<>();

        // Execute in strict reverse order for all completed steps before the failed step
        for (int i = failedStepIndex - 1; i >= 0; i--) {
            SagaStepDefinition<I> step = steps.get(i);
            log.info("Saga [{}]: Executing compensation for step {} [{}]", sagaId, i, step.getName());
            journalStore.recordStepCompensating(sagaId, i);
            if (eventBroadcaster != null) {
                eventBroadcaster.broadcast("STEP_STATE_CHANGED", sagaId, tenantId, Map.of(
                        "stepIndex", i,
                        "stepName", step.getName(),
                        "status", "COMPENSATING"
                ));
            }

            try {
                if (step.isParallel()) {
                    List<ParallelBranchDefinition<I>> branches = step.getParallelBranches();
                    for (int b = branches.size() - 1; b >= 0; b--) {
                        ParallelBranchDefinition<I> branch = branches.get(b);
                        if (branch.getCompensation() != null) {
                            branch.getCompensation().accept(context, input);
                        }
                    }
                } else if (step.getCompensation() != null) {
                    step.getCompensation().accept(context, input);
                }
                journalStore.recordStepCompensated(sagaId, i, "{\"status\":\"COMPENSATED\"}");
                compensatedStepNames.add(step.getName());
                if (metrics != null) {
                    metrics.recordStepTransition(step.getName(), "COMPENSATED");
                }
                if (tracer != null) {
                    tracer.recordCompensationSpan(sagaId, i, step.getName(), failureReason);
                }
                if (eventBroadcaster != null) {
                    eventBroadcaster.broadcast("STEP_STATE_CHANGED", sagaId, tenantId, Map.of(
                            "stepIndex", i,
                            "stepName", step.getName(),
                            "status", "COMPENSATED"
                    ));
                }
                log.info("Saga [{}]: Successfully compensated step {} [{}]", sagaId, i, step.getName());
            } catch (Exception compEx) {
                log.error("Saga [{}]: Compensation failed for step {} [{}]: {}", sagaId, i, step.getName(), compEx.getMessage());
                journalStore.recordStepFailure(sagaId, i, "Compensation failed: " + compEx.getMessage());
                journalStore.failSaga(sagaId, "Compensation failed at step " + i + ": " + compEx.getMessage());
                throw new RuntimeException("Saga compensation failed at step " + step.getName(), compEx);
            }
        }

        Map<String, Object> compensationResult = new HashMap<>();
        compensationResult.put("status", "COMPENSATED");
        String failedStepName = failedStepIndex < steps.size() ? steps.get(failedStepIndex).getName() : "unknown-step";
        compensationResult.put("failedStep", failedStepName);
        compensationResult.put("errorMessage", failureReason);
        compensationResult.put("compensatedSteps", compensatedStepNames);
        compensationResult.put("timestamp", Instant.now().toString());

        String resultJson = serialize(compensationResult);
        journalStore.completeSagaCompensation(sagaId, resultJson);
        if (outboxService != null) {
            outboxService.recordEvent("SAGA_INSTANCE", sagaId.toString(), "SAGA_COMPENSATED", resultJson, tenantId, idempotencyKey);
        }
        if (eventBroadcaster != null) {
            eventBroadcaster.broadcast("SAGA_COMPENSATED", sagaId, tenantId, Map.of(
                    "status", "COMPENSATED",
                    "failedStep", failedStepName,
                    "failureReason", failureReason
            ));
        }
        log.info("Saga [{}]: Completed backward compensation successfully", sagaId);

        throw new io.deltaflow.saga.exception.SagaCompensatedException(
                sagaId,
                idempotencyKey,
                failedStepName,
                failureReason,
                compensatedStepNames,
                cause
        );
    }

    public void validateStepFencing(UUID sagaId, int stepIndex, long responseToken) {
        SagaInstance instance = journalStore.findSaga(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga not found: " + sagaId));

        if ("COMPENSATING".equals(instance.getStatus()) || "COMPENSATED".equals(instance.getStatus())) {
            journalStore.logZombieRejected(sagaId, stepIndex, "step-" + stepIndex, responseToken, -1L, instance.getTenantId(), instance.getIdempotencyKey());
            throw new IllegalStateException("ZOMBIE_STEP_REJECTED: Saga " + sagaId + " is already in state " + instance.getStatus());
        }

        SagaStep step = journalStore.getStepsForSaga(sagaId).stream()
                .filter(s -> s.getStepIndex() == stepIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Step not found: " + stepIndex));

        if (responseToken < step.getFencingToken()) {
            journalStore.logZombieRejected(sagaId, stepIndex, step.getStepName(), responseToken, step.getFencingToken(), instance.getTenantId(), instance.getIdempotencyKey());
            throw new IllegalStateException("ZOMBIE_STEP_REJECTED: Stale fencing token " + responseToken + " < active " + step.getFencingToken());
        }
    }

    public <I, R> void recoverSaga(
            SagaDefinition<I, R> definition,
            SagaInstance instance
    ) {
        UUID sagaId = instance.getSagaId();
        log.info("Saga [{}]: Attempting autonomous crash recovery. Current status: {}", sagaId, instance.getStatus());

        List<SagaStep> recordedSteps = journalStore.getStepsForSaga(sagaId);
        int lastRecordedStepIndex = -1;
        SagaStep lastStep = null;
        for (SagaStep step : recordedSteps) {
            if (step.getStepIndex() > lastRecordedStepIndex) {
                lastRecordedStepIndex = step.getStepIndex();
                lastStep = step;
            }
        }

        I input = null;
        if (instance.getPayload() != null) {
            try {
                if (definition.getInputType() != null) {
                    input = objectMapper.readValue(instance.getPayload(), definition.getInputType());
                } else {
                    input = (I) objectMapper.readValue(instance.getPayload(), Object.class);
                }
            } catch (Exception e) {
                log.warn("Failed to deserialize payload for saga [{}]: {}", sagaId, e.getMessage());
            }
        }
        SagaExecutionContext context = new SagaExecutionContext(sagaId, instance.getTenantId(), instance.getIdempotencyKey());

        for (SagaStep step : recordedSteps) {
            if ("COMPLETED".equals(step.getStatus()) && step.getResponsePayload() != null) {
                try {
                    Map<String, Object> respMap = objectMapper.readValue(step.getResponsePayload(), Map.class);
                    context = context.withStepResult(step.getStepName(), respMap);
                } catch (Exception ignored) {}
            }
        }

        try {
            if ("COMPENSATING".equals(instance.getStatus())) {
                compensateSaga(definition, sagaId, instance.getTenantId(), instance.getIdempotencyKey(), input, context, instance.getCurrentStep(), "Crash recovery resuming compensation");
            } else if ("EXECUTING".equals(instance.getStatus())) {
                int stepIdxToCompensate = lastStep != null ? lastStep.getStepIndex() : 0;
                compensateSaga(definition, sagaId, instance.getTenantId(), instance.getIdempotencyKey(), input, context, stepIdxToCompensate, "Crash recovery after expired lease");
            } else if ("TIMED_OUT".equals(instance.getStatus()) || "SUSPENDED".equals(instance.getStatus())) {
                int stepIdxToCompensate = instance.getCurrentStep();
                compensateSaga(definition, sagaId, instance.getTenantId(), instance.getIdempotencyKey(), input, context, stepIdxToCompensate, "Asynchronous step deadline expired");
            }
        } catch (io.deltaflow.saga.exception.SagaCompensatedException e) {
            log.info("Saga [{}] successfully recovered to COMPENSATED state", sagaId);
        }
    }

    public SagaState transitionSagaState(SagaState currentState, SagaEvent event) {
        return switch (currentState) {
            case SagaState.Pending p -> switch (event) {
                case SagaEvent.StartExecution e -> new SagaState.Executing(p.sagaId(), p.tenantId(), 0, e.stepName(), Instant.now());
                case SagaEvent.FailSaga f -> new SagaState.Failed(p.sagaId(), p.tenantId(), 0, f.reason(), Instant.now());
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(p.sagaId(), p.tenantId(), c.result(), Instant.now());
                case SagaEvent.StartCompensation sc -> new SagaState.Compensating(p.sagaId(), p.tenantId(), 0, sc.cause());
                case SagaEvent.CompleteCompensation cc -> new SagaState.Compensated(p.sagaId(), p.tenantId(), cc.compensationResult(), Instant.now());
            };
            case SagaState.Executing ex -> switch (event) {
                case SagaEvent.StartExecution e -> new SagaState.Executing(ex.sagaId(), ex.tenantId(), e.stepIndex(), e.stepName(), Instant.now());
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(ex.sagaId(), ex.tenantId(), c.result(), Instant.now());
                case SagaEvent.FailSaga f -> new SagaState.Failed(ex.sagaId(), ex.tenantId(), ex.currentStepIndex(), f.reason(), Instant.now());
                case SagaEvent.StartCompensation sc -> new SagaState.Compensating(ex.sagaId(), ex.tenantId(), ex.currentStepIndex(), sc.cause());
                case SagaEvent.CompleteCompensation cc -> new SagaState.Compensated(ex.sagaId(), ex.tenantId(), cc.compensationResult(), Instant.now());
            };
            case SagaState.Completed c -> c;
            case SagaState.Failed f -> f;
            case SagaState.Compensating cmp -> switch (event) {
                case SagaEvent.FailSaga f -> new SagaState.Failed(cmp.sagaId(), cmp.tenantId(), cmp.rollbackStepIndex(), f.reason(), Instant.now());
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(cmp.sagaId(), cmp.tenantId(), c.result(), Instant.now());
                case SagaEvent.StartExecution e -> cmp;
                case SagaEvent.StartCompensation sc -> cmp;
                case SagaEvent.CompleteCompensation cc -> new SagaState.Compensated(cmp.sagaId(), cmp.tenantId(), cc.compensationResult(), Instant.now());
            };
            case SagaState.Suspended susp -> switch (event) {
                case SagaEvent.StartExecution e -> new SagaState.Executing(susp.sagaId(), susp.tenantId(), e.stepIndex(), e.stepName(), Instant.now());
                case SagaEvent.FailSaga f -> new SagaState.Failed(susp.sagaId(), susp.tenantId(), susp.currentStepIndex(), f.reason(), Instant.now());
                case SagaEvent.StartCompensation sc -> new SagaState.Compensating(susp.sagaId(), susp.tenantId(), susp.currentStepIndex(), sc.cause());
                case SagaEvent.CompleteCompensation cc -> new SagaState.Compensated(susp.sagaId(), susp.tenantId(), cc.compensationResult(), Instant.now());
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(susp.sagaId(), susp.tenantId(), c.result(), Instant.now());
            };
            case SagaState.TimedOut to -> switch (event) {
                case SagaEvent.StartCompensation sc -> new SagaState.Compensating(to.sagaId(), to.tenantId(), to.failedStepIndex(), sc.cause());
                case SagaEvent.CompleteCompensation cc -> new SagaState.Compensated(to.sagaId(), to.tenantId(), cc.compensationResult(), Instant.now());
                case SagaEvent.FailSaga f -> new SagaState.Failed(to.sagaId(), to.tenantId(), to.failedStepIndex(), f.reason(), Instant.now());
                case SagaEvent.StartExecution e -> to;
                case SagaEvent.CompleteSaga c -> new SagaState.Completed(to.sagaId(), to.tenantId(), c.result(), Instant.now());
            };
            case SagaState.Compensated cd -> cd;
        };
    }

    public SagaStepState transitionStepState(SagaStepState currentState, StepEvent event) {
        return switch (currentState) {
            case SagaStepState.NotStarted ns -> switch (event) {
                case StepEvent.Start ex -> new SagaStepState.Executing(ns.stepId(), ns.stepIndex(), ns.stepName(), ex.requestPayload(), Instant.now());
                case StepEvent.Fail fl -> new SagaStepState.Failed(ns.stepId(), ns.stepIndex(), ns.stepName(), fl.errorDetails(), Instant.now());
                case StepEvent.Complete cp -> new SagaStepState.Completed(ns.stepId(), ns.stepIndex(), ns.stepName(), cp.responsePayload(), Instant.now());
                case StepEvent.StartCompensation sc -> new SagaStepState.Compensating(ns.stepId(), ns.stepIndex(), ns.stepName(), Instant.now());
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(ns.stepId(), ns.stepIndex(), ns.stepName(), Instant.now());
            };
            case SagaStepState.Executing ex -> switch (event) {
                case StepEvent.Complete cp -> new SagaStepState.Completed(ex.stepId(), ex.stepIndex(), ex.stepName(), cp.responsePayload(), Instant.now());
                case StepEvent.Fail fl -> new SagaStepState.Failed(ex.stepId(), ex.stepIndex(), ex.stepName(), fl.errorDetails(), Instant.now());
                case StepEvent.StartCompensation sc -> new SagaStepState.Compensating(ex.stepId(), ex.stepIndex(), ex.stepName(), Instant.now());
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(ex.stepId(), ex.stepIndex(), ex.stepName(), Instant.now());
                case StepEvent.Start s -> ex;
            };
            case SagaStepState.Suspended susp -> switch (event) {
                case StepEvent.Complete cp -> new SagaStepState.Completed(susp.stepId(), susp.stepIndex(), susp.stepName(), cp.responsePayload(), Instant.now());
                case StepEvent.Fail fl -> new SagaStepState.Failed(susp.stepId(), susp.stepIndex(), susp.stepName(), fl.errorDetails(), Instant.now());
                case StepEvent.StartCompensation sc -> new SagaStepState.Compensating(susp.stepId(), susp.stepIndex(), susp.stepName(), Instant.now());
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(susp.stepId(), susp.stepIndex(), susp.stepName(), Instant.now());
                case StepEvent.Start s -> new SagaStepState.Executing(susp.stepId(), susp.stepIndex(), susp.stepName(), s.requestPayload(), Instant.now());
            };
            case SagaStepState.Completed cp -> switch (event) {
                case StepEvent.StartCompensation sc -> new SagaStepState.Compensating(cp.stepId(), cp.stepIndex(), cp.stepName(), Instant.now());
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(cp.stepId(), cp.stepIndex(), cp.stepName(), Instant.now());
                case StepEvent.Complete c -> cp;
                case StepEvent.Fail f -> cp;
                case StepEvent.Start s -> cp;
            };
            case SagaStepState.Compensating cmp -> switch (event) {
                case StepEvent.Compensate cm -> new SagaStepState.Compensated(cmp.stepId(), cmp.stepIndex(), cmp.stepName(), Instant.now());
                case StepEvent.Fail f -> new SagaStepState.Failed(cmp.stepId(), cmp.stepIndex(), cmp.stepName(), f.errorDetails(), Instant.now());
                case StepEvent.StartCompensation sc -> cmp;
                case StepEvent.Start s -> cmp;
                case StepEvent.Complete c -> cmp;
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
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return trimmed;
            }
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return "{\"value\":\"" + object.toString() + "\"}";
        }
    }
}
