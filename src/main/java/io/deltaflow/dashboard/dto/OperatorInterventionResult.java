package io.deltaflow.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

public record OperatorInterventionResult(
        UUID sagaId,
        String actionTaken,
        String previousStatus,
        String newStatus,
        String operatorId,
        String auditEventId,
        Instant timestamp
) {
}
