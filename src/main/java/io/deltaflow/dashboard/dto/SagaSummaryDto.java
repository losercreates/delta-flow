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
        Instant createdAt,
        Instant updatedAt
) {
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
                instance.getCreatedAt(),
                instance.getUpdatedAt()
        );
    }
}
