package io.deltaflow.saga.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SagaExecutionContext {

    private final UUID sagaId;
    private final String tenantId;
    private final String idempotencyKey;
    private final Map<String, Object> stepResults;

    public SagaExecutionContext(UUID sagaId, String tenantId, String idempotencyKey) {
        this(sagaId, tenantId, idempotencyKey, Collections.emptyMap());
    }

    public SagaExecutionContext(UUID sagaId, String tenantId, String idempotencyKey, Map<String, Object> stepResults) {
        this.sagaId = sagaId;
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.stepResults = Collections.unmodifiableMap(new HashMap<>(stepResults != null ? stepResults : Collections.emptyMap()));
    }

    public SagaExecutionContext withStepResult(String stepName, Object result) {
        Map<String, Object> updated = new HashMap<>(this.stepResults);
        updated.put(stepName, result);
        return new SagaExecutionContext(this.sagaId, this.tenantId, this.idempotencyKey, updated);
    }

    public UUID getSagaId() {
        return sagaId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Map<String, Object> getStepResults() {
        return stepResults;
    }

    public Object getStepResult(String stepName) {
        return stepResults.get(stepName);
    }

    @SuppressWarnings("unchecked")
    public <T> T getStepResult(String stepName, Class<T> clazz) {
        Object val = stepResults.get(stepName);
        if (val == null) {
            return null;
        }
        if (clazz.isInstance(val)) {
            return (T) val;
        }
        throw new ClassCastException("Step result for '" + stepName + "' is of type " + val.getClass().getName() + ", expected " + clazz.getName());
    }
}

