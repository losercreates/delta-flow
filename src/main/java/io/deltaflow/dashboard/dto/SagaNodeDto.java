package io.deltaflow.dashboard.dto;

import java.time.Instant;

public record SagaNodeDto(
        String id,
        int stepIndex,
        String stepName,
        String status,
        boolean isParallel,
        String branchName,
        Long fencingToken,
        String requestPayload,
        String responsePayload,
        String errorDetails,
        Instant startedAt,
        Instant completedAt
) {
}
