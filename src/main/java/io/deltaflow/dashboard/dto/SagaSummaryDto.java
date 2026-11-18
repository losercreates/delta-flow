package io.deltaflow.dashboard.dto;

import io.deltaflow.saga.domain.SagaInstance;

import java.time.Instant;
import java.util.UUID;

public record SagaSummaryDto(
        UUID sagaId,
        String sagaType,
        String status,
        String tenantId,
        String idempotencyKey,
        int currentStep,
        String awaitingEventName,
        Instant stepDeadline,
        Instant createdAt,
        Instant updatedAt
) {
    public SagaSummaryDto(
            UUID sagaId,
            String sagaType,
            String status,
            String tenantId,
            String idempotencyKey,
            int currentStep,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(sagaId, sagaType, status, tenantId, idempotencyKey, currentStep, null, null, createdAt, updatedAt);
    }

    public static SagaSummaryDto from(SagaInstance instance) {
        if (instance == null) {
            return null;
        }
        return new SagaSummaryDto(
                instance.getSagaId(),
                instance.getSagaType(),
                instance.getStatus(),
                instance.getTenantId(),
                instance.getIdempotencyKey(),
                instance.getCurrentStep(),
                instance.getAwaitingEventName(),
                instance.getStepDeadline(),
                instance.getCreatedAt(),
                instance.getUpdatedAt()
        );
    }
}
