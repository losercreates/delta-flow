package io.deltaflow.idempotency.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.common.exception.IdempotencyConflictException;
import io.deltaflow.common.exception.PayloadMismatchException;
import io.deltaflow.idempotency.domain.*;
import io.deltaflow.idempotency.lock.DistributedLockManager;
import io.deltaflow.idempotency.lock.PostgresLockFallback;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class IdempotencyCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCoordinator.class);

    private final IdempotencyRecordRepository repository;
    private final DistributedLockManager distributedLockManager;
    private final PostgresLockFallback postgresLockFallback;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    @Value("${deltaflow.idempotency.lease-ttl:30s}")
    private Duration leaseTtl;

    @Value("${deltaflow.idempotency.contender-poll-interval:50ms}")
    private Duration pollInterval;

    @Value("${deltaflow.idempotency.contender-max-wait:3s}")
    private Duration maxWaitBudget;

    public IdempotencyCoordinator(
        IdempotencyRecordRepository repository,
        @Autowired(required = false) DistributedLockManager distributedLockManager,
        PostgresLockFallback postgresLockFallback,
        AuditLogger auditLogger,
        ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.distributedLockManager = distributedLockManager;
        this.postgresLockFallback = postgresLockFallback;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempts to acquire an idempotency lock or returns cached terminal result.
     */
    public LockAcquisitionResult prepareOrResolve(String tenantId, String idempotencyKey, String requestHash) {
        Optional<IdempotencyRecord> recordOpt = repository.findById(tenantId, idempotencyKey);

        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();

            // US2: Tampering check
            if (!Objects.equals(record.getRequestHash(), requestHash)) {
                auditLogger.logEvent("PAYLOAD_MISMATCH", tenantId, idempotencyKey, Map.of(
                    "existingHash", record.getRequestHash(),
                    "incomingHash", requestHash
                ));
                throw new PayloadMismatchException(idempotencyKey, record.getRequestHash(), requestHash);
            }

            // US1: Check if already resolved
            if ("RESOLVED".equalsIgnoreCase(record.getStatus())) {
                auditLogger.logEvent("CACHE_HIT", tenantId, idempotencyKey, Map.of("status", "RESOLVED"));
                return toResolvedResult(record);
            }

            // US4: Lease expiration recovery
            Instant now = Instant.now();
            if (record.getLeaseExpiresAt() != null && record.getLeaseExpiresAt().isBefore(now)) {
                log.warn("Lease expired for tenant: {}, key: {}. Reclaiming lock.", tenantId, idempotencyKey);
                auditLogger.logEvent("LEASE_EXPIRED_RECLAIMED", tenantId, idempotencyKey, Map.of(
                    "expiredAt", record.getLeaseExpiresAt().toString()
                ));
                Optional<LockAcquisitionResult.Acquired> acquiredOpt = tryAcquireLockAndPersist(tenantId, idempotencyKey, requestHash, record);
                if (acquiredOpt.isPresent()) {
                    return acquiredOpt.get();
                }
                return pollForResolutionOrConflict(tenantId, idempotencyKey, requestHash);
            }

            // US3: Active in-flight processing - wait and poll or conflict
            return pollForResolutionOrConflict(tenantId, idempotencyKey, requestHash);
        }

        // New record - attempt lock acquisition
        Optional<LockAcquisitionResult.Acquired> acquiredOpt = tryAcquireLockAndPersist(tenantId, idempotencyKey, requestHash, null);
        if (acquiredOpt.isPresent()) {
            return acquiredOpt.get();
        }

        // Another concurrent request acquired the lock first or already resolved
        Optional<IdempotencyRecord> recheck = repository.findById(tenantId, idempotencyKey);
        if (recheck.isPresent() && "RESOLVED".equalsIgnoreCase(recheck.get().getStatus())) {
            auditLogger.logEvent("CACHE_HIT", tenantId, idempotencyKey, Map.of("status", "RESOLVED"));
            return toResolvedResult(recheck.get());
        }

        return pollForResolutionOrConflict(tenantId, idempotencyKey, requestHash);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<LockAcquisitionResult.Acquired> tryAcquireLockAndPersist(
        String tenantId, 
        String idempotencyKey, 
        String requestHash, 
        IdempotencyRecord existingRecord
    ) {
        Optional<String> tokenOpt = Optional.empty();
        boolean primaryLockAttempted = false;

        if (distributedLockManager != null) {
            try {
                tokenOpt = distributedLockManager.acquireLock(tenantId, idempotencyKey, leaseTtl);
                primaryLockAttempted = true;
            } catch (Exception e) {
                log.warn("Primary distributed lock unavailable, invoking PostgreSQL fallback: {}", e.getMessage());
                primaryLockAttempted = false;
            }
        }

        if (!primaryLockAttempted) {
            tokenOpt = postgresLockFallback.acquireLock(tenantId, idempotencyKey, requestHash, leaseTtl);
        }

        if (tokenOpt.isEmpty()) {
            return Optional.empty();
        }

        String token = tokenOpt.get();
        Instant leaseExpiresAt = Instant.now().plus(leaseTtl);

        // When primary distributed lock (Redis) succeeded, double-check database state
        if (primaryLockAttempted) {
            Optional<IdempotencyRecord> currentOpt = repository.findById(tenantId, idempotencyKey);
            if (currentOpt.isPresent()) {
                IdempotencyRecord current = currentOpt.get();
                if ("RESOLVED".equalsIgnoreCase(current.getStatus())) {
                    log.info("Key already resolved while acquiring lock: {}. Releasing lock.", idempotencyKey);
                    distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
                    return Optional.empty();
                }
                if ("PROCESSING".equalsIgnoreCase(current.getStatus()) && current.getLeaseExpiresAt() != null && current.getLeaseExpiresAt().isAfter(Instant.now())) {
                    if (existingRecord == null) {
                        log.info("Key actively processing while acquiring lock: {}. Releasing lock.", idempotencyKey);
                        distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
                        return Optional.empty();
                    }
                }
            }

            IdempotencyRecord record = currentOpt.orElseGet(() -> existingRecord != null ? existingRecord : new IdempotencyRecord(
                new IdempotencyRecordId(tenantId, idempotencyKey),
                requestHash,
                "PROCESSING",
                leaseExpiresAt
            ));
            record.setStatus("PROCESSING");
            record.setLockToken(token);
            record.setLeaseExpiresAt(leaseExpiresAt);
            record.setRequestHash(requestHash);
            repository.saveAndFlush(record);
        }

        auditLogger.logEvent("LOCK_ACQUIRED", tenantId, idempotencyKey, Map.of(
            "lockToken", token,
            "leaseExpiresAt", leaseExpiresAt.toString()
        ));

        return Optional.of(new LockAcquisitionResult.Acquired(token));
    }

    private LockAcquisitionResult pollForResolutionOrConflict(String tenantId, String idempotencyKey, String requestHash) {
        long deadline = System.currentTimeMillis() + maxWaitBudget.toMillis();
        long intervalMs = Math.max(20, pollInterval.toMillis());

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            Optional<IdempotencyRecord> current = repository.findById(tenantId, idempotencyKey);
            if (current.isPresent()) {
                IdempotencyRecord r = current.get();

                if (!Objects.equals(r.getRequestHash(), requestHash)) {
                    throw new PayloadMismatchException(idempotencyKey, r.getRequestHash(), requestHash);
                }

                if ("RESOLVED".equalsIgnoreCase(r.getStatus())) {
                    auditLogger.logEvent("CACHE_HIT_AFTER_WAIT", tenantId, idempotencyKey, Collections.emptyMap());
                    return toResolvedResult(r);
                }

                if ("FAILED".equalsIgnoreCase(r.getStatus())) {
                    throw new IdempotencyConflictException(
                        "IDEMPOTENCY_TRANSACTION_FAILED",
                        "Transaction execution failed on initial attempt.",
                        idempotencyKey
                    );
                }
            }
        }

        throw new IdempotencyConflictException(
            "IDEMPOTENCY_KEY_IN_FLIGHT",
            "Transaction is currently being processed by another request. Retry shortly.",
            idempotencyKey
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveResolvedResponse(
        String tenantId, 
        String idempotencyKey, 
        int statusCode, 
        Map<String, String> headers, 
        byte[] payload
    ) {
        Optional<IdempotencyRecord> recordOpt = repository.findById(tenantId, idempotencyKey);
        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();
            record.setStatus("RESOLVED");
            record.setResponseStatusCode(statusCode);
            record.setResponseHeaders(serializeHeaders(headers));
            record.setResponsePayload(payload);
            String token = record.getLockToken();
            record.setLockToken(null);
            repository.saveAndFlush(record);

            auditLogger.logEvent("RESOLVED", tenantId, idempotencyKey, Map.of(
                "statusCode", statusCode,
                "payloadBytes", payload != null ? payload.length : 0
            ));

            if (distributedLockManager != null && token != null) {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
                        }
                    });
                } else {
                    distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
                }
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String tenantId, String idempotencyKey, String reason) {
        Optional<IdempotencyRecord> recordOpt = repository.findById(tenantId, idempotencyKey);
        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();
            record.setStatus("FAILED");
            String token = record.getLockToken();
            record.setLockToken(null);
            repository.saveAndFlush(record);

            auditLogger.logEvent("FAILED", tenantId, idempotencyKey, Map.of("reason", reason != null ? reason : "unknown"));

            if (distributedLockManager != null && token != null) {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
                        }
                    });
                } else {
                    distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
                }
            }
        }
    }

    private LockAcquisitionResult.Replayed toResolvedResult(IdempotencyRecord record) {
        Map<String, String> headers = parseHeaders(record.getResponseHeaders());
        IdempotencyState.Resolved resolved = new IdempotencyState.Resolved(
            record.getId().getTenantId(),
            record.getId().getIdempotencyKey(),
            record.getRequestHash(),
            record.getResponseStatusCode() != null ? record.getResponseStatusCode() : 200,
            headers,
            record.getResponsePayload() != null ? record.getResponsePayload() : new byte[0],
            record.getUpdatedAt()
        );
        return new LockAcquisitionResult.Replayed(resolved);
    }

    private String serializeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize response headers to JSON", e);
            return "{}";
        }
    }

    private Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize response headers from JSON", e);
            return Collections.emptyMap();
        }
    }
}
