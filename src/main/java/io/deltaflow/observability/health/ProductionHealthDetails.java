package io.deltaflow.observability.health;

import java.util.Map;

public record ProductionHealthDetails(
        String status,
        String databaseStatus,
        String redisStatus,
        Map<String, String> circuitBreakers,
        long activeSagaCount,
        boolean draining,
        long uptimeSeconds
) {
}
