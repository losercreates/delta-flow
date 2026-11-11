package io.deltaflow.saga.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SagaStepDto(
        UUID stepId,
        UUID sagaId,
        String stepName,
        int stepIndex,
        String status,
        String requestPayload,
        String responsePayload,
        String errorDetails,
        long fencingToken,
        Instant createdAt,
        Instant updatedAt
) {
    public SagaStepDto(
            UUID stepId,
            UUID sagaId,
            String stepName,
            int stepIndex,
            String status,
            String requestPayload,
            String responsePayload,
            String errorDetails,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(stepId, sagaId, stepName, stepIndex, status, requestPayload, responsePayload, errorDetails, 1L, createdAt, updatedAt);
    }
}
