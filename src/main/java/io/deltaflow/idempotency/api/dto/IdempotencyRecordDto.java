package io.deltaflow.idempotency.api.dto;

import java.time.Instant;

public record IdempotencyRecordDto(
    String tenantId,
    String idempotencyKey,
    String requestHash,
    String status,
    Integer responseStatusCode,
    Instant leaseExpiresAt,
    Instant createdAt,
    Instant updatedAt
) {}

