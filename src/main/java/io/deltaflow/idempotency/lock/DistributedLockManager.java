package io.deltaflow.idempotency.lock;

import java.time.Duration;
import java.util.Optional;

public interface DistributedLockManager {

    /**
     * Attempts to acquire a distributed lock for the given tenant and idempotency key.
     * @param tenantId the tenant ID
     * @param idempotencyKey the idempotency key
     * @param leaseDuration the duration of the lock lease
     * @return an Optional containing the lock token if acquired, or empty if lock could not be acquired
     */
    Optional<String> acquireLock(String tenantId, String idempotencyKey, Duration leaseDuration);

    /**
     * Releases a distributed lock using the lock token.
     */
    boolean releaseLock(String tenantId, String idempotencyKey, String lockToken);

    /**
     * Extends an existing lock lease.
     */
    boolean extendLease(String tenantId, String idempotencyKey, String lockToken, Duration extension);
}

