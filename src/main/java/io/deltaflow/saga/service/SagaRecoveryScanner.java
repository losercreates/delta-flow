package io.deltaflow.saga.service;

import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autonomous background daemon detecting expired in-flight saga leases on startup and periodically.
 * Claims leases using pessimistic locking (FOR UPDATE SKIP LOCKED) in a dedicated claim transaction,
 * and then executes recovery/compensation using the WAL journal store.
 */
@Service
public class SagaRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryScanner.class);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaCoordinator sagaCoordinator;
    private final AuditLogger auditLogger;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, SagaDefinition<?, ?>> sagaDefinitions = new ConcurrentHashMap<>();
    private final String workerId = UUID.randomUUID().toString();

    @Value("${deltaflow.saga.recovery.enabled:true}")
    private boolean enabled;

    @Value("${deltaflow.saga.recovery.lease-timeout-seconds:5}")
    private int leaseTimeoutSeconds;

    public SagaRecoveryScanner(
            SagaInstanceRepository sagaInstanceRepository,
            SagaCoordinator sagaCoordinator,
            AuditLogger auditLogger,
            List<SagaDefinition<?, ?>> definitions,
            PlatformTransactionManager transactionManager
    ) {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaCoordinator = sagaCoordinator;
        this.auditLogger = auditLogger;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        for (SagaDefinition<?, ?> def : definitions) {
            this.sagaDefinitions.put(def.getName(), def);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled) {
            log.info("Saga recovery scanner is disabled by configuration");
            return;
        }
        log.info("Running initial startup crash recovery scan [worker: {}]", workerId);
        runRecoveryScan();
    }

    @Scheduled(fixedDelayString = "${deltaflow.saga.recovery.poll-interval:5000}")
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        runRecoveryScan();
    }

    /**
     * Executes recovery scan:
     * 1. Claims expired in-flight sagas in a short atomic transaction with FOR UPDATE SKIP LOCKED
     *    and lease extension to prevent concurrent worker contention.
     * 2. Recovers/compensates each claimed saga outside the claim transaction to allow independent
     *    WAL transactions (REQUIRES_NEW) without deadlocks.
     */
    public int runRecoveryScan() {
        Instant threshold = Instant.now().minusSeconds(leaseTimeoutSeconds);

        List<SagaInstance> claimedSagas = transactionTemplate.execute(status -> {
            List<SagaInstance> expired = sagaInstanceRepository.findExpiredInFlightForUpdate(threshold);
            if (expired.isEmpty()) {
                return Collections.emptyList();
            }
            List<SagaInstance> claimed = new ArrayList<>(expired.size());
            for (SagaInstance saga : expired) {
                saga.setUpdatedAt(Instant.now());
                claimed.add(sagaInstanceRepository.save(saga));
            }
            return claimed;
        });

        if (claimedSagas == null || claimedSagas.isEmpty()) {
            return 0;
        }

        log.info("Recovery scanner [worker: {}] claimed {} expired saga(s)", workerId, claimedSagas.size());
        int recoveredCount = 0;

        for (SagaInstance instance : claimedSagas) {
            UUID sagaId = instance.getSagaId();
            String tenantId = instance.getTenantId();
            String idempotencyKey = instance.getIdempotencyKey();
            String sagaType = instance.getSagaType();

            // Emit RECOVERY_CLAIMED audit event
            auditLogger.logEvent("RECOVERY_CLAIMED", tenantId, idempotencyKey, Map.of(
                    "sagaId", sagaId.toString(),
                    "workerId", workerId,
                    "previousStatus", instance.getStatus(),
                    "leaseExpiredAt", instance.getUpdatedAt().toString()
            ));

            SagaDefinition<?, ?> definition = sagaDefinitions.get(sagaType);
            if (definition == null) {
                log.error("Unknown saga type [{}] for saga [{}], skipping recovery", sagaType, sagaId);
                continue;
            }

            try {
                sagaCoordinator.recoverSaga(definition, instance);

                // Emit RECOVERY_COMPLETED audit event
                auditLogger.logEvent("RECOVERY_COMPLETED", tenantId, idempotencyKey, Map.of(
                        "sagaId", sagaId.toString(),
                        "workerId", workerId,
                        "recoveredStatus", "COMPENSATED"
                ));
                recoveredCount++;
            } catch (Exception e) {
                log.error("Failed to recover saga [{}]: {}", sagaId, e.getMessage(), e);
            }
        }

        return recoveredCount;
    }

    public String getWorkerId() {
        return workerId;
    }
}
