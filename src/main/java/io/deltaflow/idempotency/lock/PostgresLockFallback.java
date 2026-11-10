package io.deltaflow.idempotency.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class PostgresLockFallback {

    private static final Logger log = LoggerFactory.getLogger(PostgresLockFallback.class);
    private final JdbcTemplate jdbcTemplate;

    public PostgresLockFallback(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> acquireLock(String tenantId, String idempotencyKey, String requestHash, Duration leaseDuration) {
        log.info("Acquiring PostgreSQL pessimistic row lock fallback for tenant: {}, key: {}", tenantId, idempotencyKey);
        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plus(leaseDuration);
        String lockToken = UUID.randomUUID().toString();

        // 1. Try atomic insert with ON CONFLICT DO NOTHING
        String insertSql = """
            INSERT INTO idempotency_records 
                (tenant_id, idempotency_key, request_hash, status, lock_token, lease_expires_at, created_at, updated_at)
            VALUES (?, ?, ?, 'PROCESSING', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """;

        try {
            int inserted = jdbcTemplate.update(
                insertSql,
                tenantId,
                idempotencyKey,
                requestHash,
                lockToken,
                Timestamp.from(leaseExpiresAt)
            );
            if (inserted > 0) {
                log.info("PostgreSQL fallback lock acquired (new record) for key: {}", idempotencyKey);
                return Optional.of(lockToken);
            }
        } catch (Exception e) {
            log.warn("Error during PostgreSQL insert fallback for key: {}", idempotencyKey, e);
            return Optional.empty();
        }

        // 2. If row exists, attempt to atomically reclaim if expired
        String updateExpiredSql = """
            UPDATE idempotency_records
            SET lock_token = ?, lease_expires_at = ?, status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND idempotency_key = ? 
              AND status = 'PROCESSING' 
              AND lease_expires_at < ?
            """;

        try {
            int updated = jdbcTemplate.update(
                updateExpiredSql,
                lockToken,
                Timestamp.from(leaseExpiresAt),
                tenantId,
                idempotencyKey,
                Timestamp.from(now)
            );
            if (updated > 0) {
                log.info("PostgreSQL fallback lock reclaimed (expired lease) for key: {}", idempotencyKey);
                return Optional.of(lockToken);
            }
        } catch (Exception e) {
            log.warn("Error during PostgreSQL update fallback for key: {}", idempotencyKey, e);
            return Optional.empty();
        }

        log.debug("PostgreSQL fallback lock held by another process for key: {}", idempotencyKey);
        return Optional.empty();
    }
}
