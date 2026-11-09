package io.deltaflow.idempotency.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Sealed hierarchy representing the discrete lifecycle states of an idempotency record.
 * Designed for compile-time exhaustiveness checks in switch pattern matching.
 */
public sealed interface IdempotencyState permits 
    IdempotencyState.Pending, 
    IdempotencyState.Processing, 
    IdempotencyState.Resolved, 
    IdempotencyState.Failed {

    String tenantId();
    String idempotencyKey();
    String requestHash();

    record Pending(
        String tenantId, 
        String idempotencyKey, 
        String requestHash, 
        String lockToken, 
        Instant leaseExpiresAt
    ) implements IdempotencyState {}

    record Processing(
        String tenantId, 
        String idempotencyKey, 
        String requestHash, 
        String lockToken, 
        Instant leaseExpiresAt
    ) implements IdempotencyState {}

    record Resolved(
        String tenantId, 
        String idempotencyKey, 
        String requestHash, 
        int statusCode, 
        Map<String, String> headers, 
        byte[] payload, 
        Instant resolvedAt
    ) implements IdempotencyState {}

    record Failed(
        String tenantId, 
        String idempotencyKey, 
        String requestHash, 
        String failureReason, 
        Instant failedAt
    ) implements IdempotencyState {}
}

