package io.deltaflow.outbox.api.dto;

import io.deltaflow.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventDto(
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        String status,
        int retryCount,
        String errorDetails,
        Instant createdAt,
        Instant publishedAt
) {
    public static OutboxEventDto from(OutboxEvent event) {
        if (event == null) {
            return null;
        }
        return new OutboxEventDto(
                event.getEventId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getPayload(),
                event.getStatus(),
                event.getRetryCount(),
                event.getErrorDetails(),
                event.getCreatedAt(),
                event.getPublishedAt()
        );
    }
}

