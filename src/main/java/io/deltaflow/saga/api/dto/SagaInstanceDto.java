package io.deltaflow.saga.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SagaInstanceDto(
        UUID sagaId,
        String tenantId,
        String idempotencyKey,
        String sagaType,
        String status,
        int currentStep,
        String payload,
        String resultPayload,
        Instant createdAt,
        Instant updatedAt
) {}

