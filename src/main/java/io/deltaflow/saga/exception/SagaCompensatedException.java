package io.deltaflow.saga.exception;

import java.util.List;
import java.util.UUID;

public class SagaCompensatedException extends RuntimeException {

    private final UUID sagaId;
    private final String idempotencyKey;
    private final String failedStep;
    private final List<String> compensatedSteps;

    public SagaCompensatedException(
            UUID sagaId,
            String idempotencyKey,
            String failedStep,
            String errorMessage,
            List<String> compensatedSteps,
            Throwable cause
    ) {
        super(errorMessage, cause);
        this.sagaId = sagaId;
        this.idempotencyKey = idempotencyKey;
        this.failedStep = failedStep;
        this.compensatedSteps = compensatedSteps != null ? List.copyOf(compensatedSteps) : List.of();
    }

    public UUID getSagaId() {
        return sagaId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getFailedStep() {
        return failedStep;
    }

    public List<String> getCompensatedSteps() {
        return compensatedSteps;
    }
}

