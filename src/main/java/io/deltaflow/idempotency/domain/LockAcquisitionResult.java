package io.deltaflow.idempotency.domain;

/**
 * Result of attempting to acquire an idempotency lock or resolve an existing record.
 */
public sealed interface LockAcquisitionResult permits 
    LockAcquisitionResult.Acquired,
    LockAcquisitionResult.Replayed,
    LockAcquisitionResult.PayloadMismatch,
    LockAcquisitionResult.ConcurrentConflict {

    record Acquired(String lockToken) implements LockAcquisitionResult {}
    record Replayed(IdempotencyState.Resolved resolved) implements LockAcquisitionResult {}
    record PayloadMismatch(String existingHash, String incomingHash) implements LockAcquisitionResult {}
    record ConcurrentConflict(String idempotencyKey, IdempotencyState state) implements LockAcquisitionResult {}
}

