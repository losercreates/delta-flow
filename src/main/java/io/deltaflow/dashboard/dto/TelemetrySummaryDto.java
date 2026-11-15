package io.deltaflow.dashboard.dto;

import java.util.List;

public record TelemetrySummaryDto(
        long activeSagas,
        long completedSagas,
        long compensatedSagas,
        long failedSagas,
        long pendingOutboxEvents,
        long deadLetterEvents,
        List<CircuitBreakerStatusDto> circuitBreakers
) {
}
