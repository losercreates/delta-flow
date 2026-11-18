package io.deltaflow.saga.exception;

import java.time.Instant;
import java.util.UUID;

public class SagaSuspendedException extends RuntimeException {

    private final UUID sagaId;
    private final String tenantId;
    private final String idempotencyKey;
    private final int stepIndex;
    private final String stepName;
    private final String awaitingEventName;
    private final Instant stepDeadline;

    public SagaSuspendedException(
            UUID sagaId,
            String tenantId,
            String idempotencyKey,
            int stepIndex,
            String stepName,
            String awaitingEventName,
            Instant stepDeadline
    ) {
        super("Saga [" + sagaId + "] suspended at step " + stepIndex + " [" + stepName + "] awaiting event [" + awaitingEventName + "]");
        this.sagaId = sagaId;
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.stepIndex = stepIndex;
        this.stepName = stepName;
        this.awaitingEventName = awaitingEventName;
        this.stepDeadline = stepDeadline;
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

    public int getStepIndex() {
        return stepIndex;
    }

    public String getStepName() {
        return stepName;
    }

    public String getAwaitingEventName() {
        return awaitingEventName;
    }

    public Instant getStepDeadline() {
        return stepDeadline;
    }
}
