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

    @Value("${deltaflow.idempotency.contender-poll-interval:100ms}")
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
    @Transactional(propagation = Propagation.REQUIRED)
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
                Map<String, String> headers = parseHeaders(record.getResponseHeaders());
                IdempotencyState.Resolved resolved = new IdempotencyState.Resolved(
                    tenantId,
                    idempotencyKey,
                    record.getRequestHash(),
                    record.getResponseStatusCode() != null ? record.getResponseStatusCode() : 200,
                    headers,
                    record.getResponsePayload() != null ? record.getResponsePayload() : new byte[0],
                    record.getUpdatedAt()
                );
                auditLogger.logEvent("CACHE_HIT", tenantId, idempotencyKey, Map.of("status", "RESOLVED"));
                return new LockAcquisitionResult.Replayed(resolved);
            }

            // US4: Lease expiration recovery
            Instant now = Instant.now();
            if (record.getLeaseExpiresAt() != null && record.getLeaseExpiresAt().isBefore(now)) {
                log.warn("Lease expired for tenant: {}, key: {}. Reclaiming lock.", tenantId, idempotencyKey);
                auditLogger.logEvent("LEASE_EXPIRED_RECLAIMED", tenantId, idempotencyKey, Map.of(
                    "expiredAt", record.getLeaseExpiresAt().toString()
                ));
                return acquireLockAndPersist(tenantId, idempotencyKey, requestHash, record);
            }

            // US3: Active in-flight processing - wait and poll or conflict
            return pollForResolutionOrConflict(tenantId, idempotencyKey, requestHash);
        }

        // New record - acquire lock
        return acquireLockAndPersist(tenantId, idempotencyKey, requestHash, null);
    }

    private LockAcquisitionResult acquireLockAndPersist(
        String tenantId, 
        String idempotencyKey, 
        String requestHash, 
        IdempotencyRecord existingRecord
    ) {
        Optional<String> tokenOpt = Optional.empty();

        if (distributedLockManager != null) {
            tokenOpt = distributedLockManager.acquireLock(tenantId, idempotencyKey, leaseTtl);
        }

        if (tokenOpt.isEmpty()) {
            tokenOpt = postgresLockFallback.acquireLock(tenantId, idempotencyKey, requestHash, leaseTtl);
        }

        if (tokenOpt.isEmpty()) {
            throw new IdempotencyConflictException(
                "IDEMPOTENCY_KEY_IN_FLIGHT",
                "Another process is actively executing with this idempotency key.",
                idempotencyKey
            );
        }

        String token = tokenOpt.get();
        Instant leaseExpiresAt = Instant.now().plus(leaseTtl);

        IdempotencyRecord record = existingRecord != null ? existingRecord : new IdempotencyRecord(
            new IdempotencyRecordId(tenantId, idempotencyKey),
            requestHash,
            "PROCESSING",
            leaseExpiresAt
        );
        record.setStatus("PROCESSING");
        record.setLockToken(token);
        record.setLeaseExpiresAt(leaseExpiresAt);
        record.setRequestHash(requestHash);
        repository.save(record);

        auditLogger.logEvent("LOCK_ACQUIRED", tenantId, idempotencyKey, Map.of(
            "lockToken", token,
            "leaseExpiresAt", leaseExpiresAt.toString()
        ));

        return new LockAcquisitionResult.Acquired(token);
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
            if (current.isPresent() && "RESOLVED".equalsIgnoreCase(current.get().getStatus())) {
                IdempotencyRecord r = current.get();
                Map<String, String> headers = parseHeaders(r.getResponseHeaders());
                IdempotencyState.Resolved resolved = new IdempotencyState.Resolved(
                    tenantId,
                    idempotencyKey,
                    r.getRequestHash(),
                    r.getResponseStatusCode() != null ? r.getResponseStatusCode() : 200,
                    headers,
                    r.getResponsePayload() != null ? r.getResponsePayload() : new byte[0],
                    r.getUpdatedAt()
                );
                auditLogger.logEvent("CACHE_HIT_AFTER_WAIT", tenantId, idempotencyKey, Collections.emptyMap());
                return new LockAcquisitionResult.Replayed(resolved);
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
            repository.save(record);

            if (distributedLockManager != null && token != null) {
                distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
            }

            auditLogger.logEvent("RESOLVED", tenantId, idempotencyKey, Map.of(
                "statusCode", statusCode,
                "payloadBytes", payload != null ? payload.length : 0
            ));
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
            repository.save(record);

            if (distributedLockManager != null && token != null) {
                distributedLockManager.releaseLock(tenantId, idempotencyKey, token);
            }

            auditLogger.logEvent("FAILED", tenantId, idempotencyKey, Map.of("reason", reason != null ? reason : "unknown"));
        }
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

