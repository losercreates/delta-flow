package io.deltaflow.idempotency.lock;

import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.domain.IdempotencyRecordId;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class PostgresLockFallback {

    private static final Logger log = LoggerFactory.getLogger(PostgresLockFallback.class);
    private final IdempotencyRecordRepository repository;

    public PostgresLockFallback(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> acquireLock(String tenantId, String idempotencyKey, String requestHash, Duration leaseDuration) {
        log.info("Acquiring PostgreSQL pessimistic row lock fallback for tenant: {}, key: {}", tenantId, idempotencyKey);
        IdempotencyRecordId id = new IdempotencyRecordId(tenantId, idempotencyKey);
        Optional<IdempotencyRecord> existingOpt = repository.findForUpdate(tenantId, idempotencyKey);

        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plus(leaseDuration);
        String lockToken = UUID.randomUUID().toString();

        if (existingOpt.isEmpty()) {
            IdempotencyRecord newRecord = new IdempotencyRecord(id, requestHash, "PROCESSING", leaseExpiresAt);
            newRecord.setLockToken(lockToken);
            repository.save(newRecord);
            return Optional.of(lockToken);
        }

        IdempotencyRecord existing = existingOpt.get();
        if ("PROCESSING".equals(existing.getStatus()) && existing.getLeaseExpiresAt().isBefore(now)) {
            // Reclaim expired lease
            existing.setLockToken(lockToken);
            existing.setLeaseExpiresAt(leaseExpiresAt);
            repository.save(existing);
            return Optional.of(lockToken);
        }

        return Optional.empty();
    }
}

