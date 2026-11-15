package io.deltaflow.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SagaGraphDto(
        UUID sagaId,
        String sagaType,
        String status,
        String tenantId,
        String idempotencyKey,
        String payload,
        int currentStep,
        Instant createdAt,
        Instant updatedAt,
        List<SagaNodeDto> nodes,
        List<SagaEdgeDto> edges
) {
}
