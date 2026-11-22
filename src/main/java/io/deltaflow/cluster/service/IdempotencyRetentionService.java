package io.deltaflow.cluster.service;

import io.deltaflow.cluster.domain.RetentionSummaryDto;
import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.dashboard.service.DashboardEventBroadcaster;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automated Idempotency Retention Manager.
 * Executes bounded transactional batch purges of terminal RESOLVED idempotency records
 * older than a configurable SLA retention window (default 30 days).
 * Strictly preserves active PROCESSING leases to uphold Principle I (Zero Double-Spend).
 */
@Service
public class IdempotencyRetentionService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final AuditLogger auditLogger;
    private final TransactionTemplate transactionTemplate;

    private ClusterLeaderElectionService leaderElectionService;
    private DashboardEventBroadcaster eventBroadcaster;

    @Value("${deltaflow.retention.idempotency.enabled:true}")
    private boolean enabled;

    @Value("${deltaflow.retention.idempotency.retention-days:30}")
    private int defaultRetentionDays;

    @Value("${deltaflow.retention.idempotency.batch-size:500}")
    private int defaultBatchSize;

    private final AtomicLong totalPrunedRecords = new AtomicLong(0);
    private volatile Instant lastRunAt = null;

    public IdempotencyRetentionService(
            IdempotencyRecordRepository idempotencyRecordRepository,
            AuditLogger auditLogger,
            PlatformTransactionManager transactionManager
    ) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.auditLogger = auditLogger;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Autowired(required = false)
    public void setLeaderElectionService(ClusterLeaderElectionService leaderElectionService) {
        this.leaderElectionService = leaderElectionService;
    }

    @Autowired(required = false)
    public void setEventBroadcaster(DashboardEventBroadcaster eventBroadcaster) {
        this.eventBroadcaster = eventBroadcaster;
    }

    @Scheduled(fixedDelayString = "${deltaflow.retention.idempotency.poll-interval-ms:86400000}")
    public void scheduledRetentionCycle() {
        if (!enabled) {
            return;
        }
        if (leaderElectionService != null && !leaderElectionService.isLeader()) {
            log.debug("Node is in STANDBY; skipping scheduled retention cycle");
            return;
        }
        pruneExpiredRecords(defaultRetentionDays, defaultBatchSize, "scheduler", "Scheduled automated retention cycle");
    }

    /**
     * Executes bounded batch pruning of terminal RESOLVED records older than the cutoff.
     */
    public RetentionSummaryDto pruneExpiredRecords(
            Integer retentionDays,
            Integer batchSize,
            String operatorId,
            String justification
    ) {
        int days = (retentionDays != null && retentionDays > 0) ? retentionDays : defaultRetentionDays;
        int batch = (batchSize != null && batchSize > 0) ? batchSize : defaultBatchSize;

        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        log.info("Starting idempotency retention pruning: days={}, cutoff={}, batchSize={}, operator={}",
                days, cutoff, batch, operatorId);

        long cyclePruned = 0;
        int batchCount;

        do {
            final int currentBatchSize = batch;
            Integer deleted = transactionTemplate.execute(status ->
                    idempotencyRecordRepository.deleteExpiredResolvedBatch(cutoff, currentBatchSize)
            );
            batchCount = deleted != null ? deleted : 0;
            cyclePruned += batchCount;
        } while (batchCount >= batch);

        totalPrunedRecords.addAndGet(cyclePruned);
        lastRunAt = Instant.now();

        Optional<Instant> oldestRemaining = idempotencyRecordRepository.findOldestResolvedRecordCreatedAt();

        log.info("Idempotency retention cycle completed: prunedRecords={}, totalPruned={}",
                cyclePruned, totalPrunedRecords.get());

        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("prunedCount", cyclePruned);
        auditDetails.put("totalPruned", totalPrunedRecords.get());
        auditDetails.put("retentionDays", days);
        auditDetails.put("cutoff", cutoff.toString());
        auditDetails.put("operatorId", operatorId != null ? operatorId : "system");
        auditDetails.put("justification", justification != null ? justification : "Unspecified");
        auditLogger.logEvent("IDEMPOTENCY_RETENTION_PRUNED", operatorId != null ? operatorId : "system", "retention-cleaner", auditDetails);

        if (eventBroadcaster != null) {
            eventBroadcaster.broadcast("RETENTION_PRUNED", auditDetails);
        }

        return new RetentionSummaryDto(
                enabled,
                days,
                batch,
                lastRunAt,
                totalPrunedRecords.get(),
                oldestRemaining.orElse(null)
        );
    }

    public RetentionSummaryDto getRetentionSummary() {
        Optional<Instant> oldestRemaining = idempotencyRecordRepository.findOldestResolvedRecordCreatedAt();
        return new RetentionSummaryDto(
                enabled,
                defaultRetentionDays,
                defaultBatchSize,
                lastRunAt,
                totalPrunedRecords.get(),
                oldestRemaining.orElse(null)
        );
    }
}
