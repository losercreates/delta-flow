package io.deltaflow.saga.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class SagaJournalStore {

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepRepository sagaStepRepository;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public SagaJournalStore(
            SagaInstanceRepository sagaInstanceRepository,
            SagaStepRepository sagaStepRepository,
            AuditLogger auditLogger,
            ObjectMapper objectMapper
    ) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaStepRepository = sagaStepRepository;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaInstance createSagaInstance(
            UUID sagaId,
            String tenantId,
            String idempotencyKey,
            String sagaType,
            String payload
    ) {
        String jsonPayload = toJsonb(payload, "payload");
        SagaInstance instance = new SagaInstance(sagaId, tenantId, idempotencyKey, sagaType, jsonPayload);
        SagaInstance saved = sagaInstanceRepository.save(instance);

        Map<String, Object> details = new HashMap<>();
        details.put("sagaId", saved.getSagaId().toString());
        details.put("sagaType", saved.getSagaType());
        auditLogger.logEvent("SAGA_STARTED", saved.getTenantId(), saved.getIdempotencyKey(), details);

        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaStep recordStepIntent(
            UUID sagaId,
            int stepIndex,
            String stepName,
            String requestPayload
    ) {
        SagaInstance instance = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga instance not found: " + sagaId));

        instance.setStatus("EXECUTING");
        instance.setCurrentStep(stepIndex);
        sagaInstanceRepository.save(instance);

        SagaStep step = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, stepIndex)
                .orElseGet(() -> new SagaStep(UUID.randomUUID(), sagaId, stepName, stepIndex, "EXECUTING"));

        step.setStatus("EXECUTING");
        step.setRequestPayload(toJsonb(requestPayload, "request"));
        SagaStep savedStep = sagaStepRepository.save(step);

        Map<String, Object> details = new HashMap<>();
        details.put("sagaId", sagaId.toString());
        details.put("stepIndex", stepIndex);
        details.put("stepName", stepName);
        auditLogger.logEvent("STEP_EXECUTING", instance.getTenantId(), instance.getIdempotencyKey(), details);

        return savedStep;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaStep recordStepCompletion(
            UUID sagaId,
            int stepIndex,
            String responsePayload
    ) {
        SagaStep step = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, stepIndex)
                .orElseThrow(() -> new IllegalStateException("Saga step not found for saga: " + sagaId + " and index: " + stepIndex));

        step.setStatus("COMPLETED");
        step.setResponsePayload(toJsonb(responsePayload, "response"));
        SagaStep savedStep = sagaStepRepository.save(step);

        sagaInstanceRepository.findById(sagaId).ifPresent(instance -> {
            Map<String, Object> details = new HashMap<>();
            details.put("sagaId", sagaId.toString());
            details.put("stepIndex", stepIndex);
            details.put("stepName", savedStep.getStepName());
            auditLogger.logEvent("STEP_COMPLETED", instance.getTenantId(), instance.getIdempotencyKey(), details);
        });

        return savedStep;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaStep recordStepFailure(
            UUID sagaId,
            int stepIndex,
            String errorDetails
    ) {
        SagaStep step = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, stepIndex)
                .orElseThrow(() -> new IllegalStateException("Saga step not found for saga: " + sagaId + " and index: " + stepIndex));

        step.setStatus("FAILED");
        step.setErrorDetails(errorDetails);
        SagaStep savedStep = sagaStepRepository.save(step);

        sagaInstanceRepository.findById(sagaId).ifPresent(instance -> {
            instance.setStatus("FAILED");
            sagaInstanceRepository.save(instance);

            Map<String, Object> details = new HashMap<>();
            details.put("sagaId", sagaId.toString());
            details.put("stepIndex", stepIndex);
            details.put("stepName", savedStep.getStepName());
            details.put("errorDetails", errorDetails != null ? errorDetails : "");
            auditLogger.logEvent("STEP_FAILED", instance.getTenantId(), instance.getIdempotencyKey(), details);
        });

        return savedStep;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaInstance completeSaga(UUID sagaId, String resultPayload) {
        SagaInstance instance = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga instance not found: " + sagaId));

        instance.setStatus("COMPLETED");
        instance.setResultPayload(toJsonb(resultPayload, "result"));
        SagaInstance saved = sagaInstanceRepository.save(instance);

        Map<String, Object> details = new HashMap<>();
        details.put("sagaId", sagaId.toString());
        auditLogger.logEvent("SAGA_COMPLETED", saved.getTenantId(), saved.getIdempotencyKey(), details);

        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaInstance failSaga(UUID sagaId, String errorDetails) {
        SagaInstance instance = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga instance not found: " + sagaId));

        instance.setStatus("FAILED");
        instance.setResultPayload(toJsonb(errorDetails, "error"));
        SagaInstance saved = sagaInstanceRepository.save(instance);

        Map<String, Object> details = new HashMap<>();
        details.put("sagaId", sagaId.toString());
        details.put("errorDetails", errorDetails != null ? errorDetails : "");
        auditLogger.logEvent("SAGA_FAILED", saved.getTenantId(), saved.getIdempotencyKey(), details);

        return saved;
    }

    private String toJsonb(String value, String defaultKey) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return trimmed;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(defaultKey, value));
        } catch (JsonProcessingException e) {
            return "{\"" + defaultKey + "\":\"" + value.replace("\"", "\\\"") + "\"}";
        }
    }

    @Transactional(readOnly = true)
    public Optional<SagaInstance> findSaga(UUID sagaId) {
        return sagaInstanceRepository.findById(sagaId);
    }

    @Transactional(readOnly = true)
    public Optional<SagaInstance> findSagaByTenantAndIdempotencyKey(String tenantId, String idempotencyKey) {
        return sagaInstanceRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<SagaStep> getStepsForSaga(UUID sagaId) {
        return sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId);
    }
}

