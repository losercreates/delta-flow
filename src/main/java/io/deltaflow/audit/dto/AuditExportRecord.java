package io.deltaflow.audit.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditExportRecord(
        long sequenceNumber,
        UUID eventId,
        String eventType,
        String tenantId,
        String idempotencyKey,
        String sagaId,
        Instant timestamp,
        String operatorId,
        String maskedPayload,
        String payloadHash,
        String previousEventHash,
        String recordChecksum
) {
}
