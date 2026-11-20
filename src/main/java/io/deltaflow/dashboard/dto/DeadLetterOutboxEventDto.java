package io.deltaflow.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterOutboxEventDto(
        UUID eventId,
        UUID sagaId,
        String tenantId,
        String eventType,
        String topic,
        String partitionKey,
        String payload,
        int retryCount,
        String errorDetails,
        Instant createdAt,
        Instant updatedAt
) {}
