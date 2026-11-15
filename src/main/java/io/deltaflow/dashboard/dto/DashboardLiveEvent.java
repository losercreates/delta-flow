package io.deltaflow.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public record DashboardLiveEvent(
        String eventId,
        String eventType,
        UUID sagaId,
        String tenantId,
        Instant timestamp,
        Object payload
) {
    public static DashboardLiveEvent of(String eventType, UUID sagaId, String tenantId, Object payload) {
        return new DashboardLiveEvent(
                UUID.randomUUID().toString(),
                eventType,
                sagaId,
                tenantId,
                Instant.now(),
                payload
        );
    }
}
