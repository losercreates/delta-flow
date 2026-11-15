package io.deltaflow.dashboard.dto;

import io.deltaflow.idempotency.domain.IdempotencyRecord;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public record IdempotencyForensicDto(
        String idempotencyKey,
        String tenantId,
        String status,
        String payloadHash,
        String cachedResponse,
        Integer statusCode,
        Instant leaseExpiresAt,
        int recoveryAttempts,
        Instant createdAt,
        Instant updatedAt
) {
    public static IdempotencyForensicDto from(IdempotencyRecord record) {
        if (record == null) {
            return null;
        }
        String responseStr = record.getResponsePayload() != null
                ? new String(record.getResponsePayload(), StandardCharsets.UTF_8)
                : null;
        return new IdempotencyForensicDto(
                record.getId() != null ? record.getId().getIdempotencyKey() : null,
                record.getId() != null ? record.getId().getTenantId() : null,
                record.getStatus(),
                record.getRequestHash(),
                responseStr,
                record.getResponseStatusCode(),
                record.getLeaseExpiresAt(),
                0,
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }
}
