package io.deltaflow.idempotency.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RedisDistributedLock implements DistributedLockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);
    private final RedissonClient redissonClient;

    public RedisDistributedLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    private String buildLockKey(String tenantId, String idempotencyKey) {
        return "deltaflow:lock:" + tenantId + ":" + idempotencyKey;
    }

    @Override
    public Optional<String> acquireLock(String tenantId, String idempotencyKey, Duration leaseDuration) {
        if (redissonClient == null) {
            log.warn("Redisson client is null, lock acquisition skipped");
            return Optional.empty();
        }

        String lockKey = buildLockKey(tenantId, idempotencyKey);
        RLock lock = redissonClient.getLock(lockKey);
        String lockToken = UUID.randomUUID().toString();

        try {
            boolean acquired = lock.tryLock(0, leaseDuration.toMillis(), TimeUnit.MILLISECONDS);
            if (acquired) {
                log.debug("Acquired Redis lock for key: {}, token: {}", lockKey, lockToken);
                return Optional.of(lockToken);
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while trying to acquire lock for key: {}", lockKey);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error acquiring Redis lock for key: {}, fallback needed", lockKey, e);
            throw new RuntimeException("Redis lock service unavailable", e);
        }
    }

    @Override
    public boolean releaseLock(String tenantId, String idempotencyKey, String lockToken) {
        if (redissonClient == null) {
            return false;
        }

        String lockKey = buildLockKey(tenantId, idempotencyKey);
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Released Redis lock for key: {}", lockKey);
                return true;
            }
        } catch (Exception e) {
            log.warn("Error releasing Redis lock for key: {}", lockKey, e);
        }
        return false;
    }

    @Override
    public boolean extendLease(String tenantId, String idempotencyKey, String lockToken, Duration extension) {
        // Redisson handles lease extension via watchdog or lock expiration
        return true;
    }
}

