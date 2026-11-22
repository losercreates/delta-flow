package io.deltaflow.outbox.service;

import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.cluster.domain.OutboxLeaseReclaimResult;
import io.deltaflow.dashboard.service.DashboardEventBroadcaster;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-Flight Outbox Lease Recovery Reaper.
 * Detects outbox events stranded in IN_FLIGHT status past their lease expiration
 * due to worker JVM crash or ungraceful termination and resets them to PENDING
 * with retry increment, or DEAD_LETTER if max retries have been exhausted.
 */
@Service
public class OutboxLeaseRecoveryReaper {

    private static final Logger log = LoggerFactory.getLogger(OutboxLeaseRecoveryReaper.class);

    private final OutboxEventRepository outboxEventRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final AuditLogger auditLogger;
    private final TransactionTemplate transactionTemplate;

    private DashboardEventBroadcaster eventBroadcaster;
    private io.deltaflow.cluster.service.ClusterLeaderElectionService leaderElectionService;

    @Value("${deltaflow.outbox.recovery.enabled:true}")
    private boolean enabled;

    @Value("${deltaflow.outbox.recovery.batch-size:50}")
    private int batchSize;

    @Value("${deltaflow.outbox.max-retries:5}")
    private int maxRetries;

    public OutboxLeaseRecoveryReaper(
            OutboxEventRepository outboxEventRepository,
            DomainEventPublisher domainEventPublisher,
            AuditLogger auditLogger,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.auditLogger = auditLogger;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Autowired(required = false)
    public void setEventBroadcaster(DashboardEventBroadcaster eventBroadcaster) {
        this.eventBroadcaster = eventBroadcaster;
    }

    @Autowired(required = false)
    public void setLeaderElectionService(io.deltaflow.cluster.service.ClusterLeaderElectionService leaderElectionService) {
        this.leaderElectionService = leaderElectionService;
    }

    @Scheduled(fixedDelayString = "${deltaflow.outbox.recovery.poll-interval:5000}")
    public void scheduledReaperScan() {
        if (!enabled) {
            return;
        }
        if (leaderElectionService != null && !leaderElectionService.isLeader()) {
            return;
        }
        runReaperScan();
    }

    /**
     * Executes atomic sweep to identify and recover abandoned IN_FLIGHT events.
     * Uses pessimistic locking (FOR UPDATE SKIP LOCKED) to coordinate across clustered nodes.
     */
    public OutboxLeaseReclaimResult runReaperScan() {
        Instant now = Instant.now();
        List<OutboxEvent> expiredEvents = transactionTemplate.execute(status -> {
            List<OutboxEvent> expired = outboxEventRepository.findExpiredInFlightForUpdate(now, PageRequest.of(0, batchSize));
            if (expired == null || expired.isEmpty()) {
                return Collections.emptyList();
            }
            return expired;
        });

        if (expiredEvents == null || expiredEvents.isEmpty()) {
            return new OutboxLeaseReclaimResult(0, 0, 0, now);
        }

        int scannedCount = expiredEvents.size();
        int reclaimedCount = 0;
        int deadLetteredCount = 0;

        for (OutboxEvent event : expiredEvents) {
            final boolean isExhausted = (event.getRetryCount() >= maxRetries);

            transactionTemplate.executeWithoutResult(status -> {
                if (isExhausted) {
                    event.setStatus(OutboxStatus.DEAD_LETTER.name());
                    event.setLeaseExpiresAt(null);
                    String errorMsg = "Lease expired past max retries (" + maxRetries + ")";
                    event.setErrorDetails(errorMsg);

                    if (domainEventPublisher instanceof KafkaDomainEventPublisher kafkaPub) {
                        kafkaPub.publishToDeadLetterQueue(event, errorMsg);
                    }

                    Map<String, Object> details = new HashMap<>();
                    details.put("eventId", event.getEventId().toString());
                    details.put("retryCount", event.getRetryCount());
                    details.put("reason", errorMsg);
                    details.put("previousWorker", event.getClaimedByWorker());
                    auditLogger.logEvent("OUTBOX_DEAD_LETTER", "system", event.getAggregateId(), details);
                } else {
                    int newRetries = event.getRetryCount() + 1;
                    event.setStatus(OutboxStatus.PENDING.name());
                    event.setRetryCount(newRetries);
                    String previousWorker = event.getClaimedByWorker();
                    event.setLeaseExpiresAt(null);
                    event.setClaimedByWorker(null);

                    Map<String, Object> details = new HashMap<>();
                    details.put("eventId", event.getEventId().toString());
                    details.put("reclaimedRetryCount", newRetries);
                    details.put("previousWorker", previousWorker);
                    details.put("status", "PENDING");
                    auditLogger.logEvent("OUTBOX_LEASE_RECLAIMED", "system", event.getAggregateId(), details);
                }
                outboxEventRepository.save(event);
            });

            if (isExhausted) {
                deadLetteredCount++;
            } else {
                reclaimedCount++;
            }
        }

        log.info("Outbox lease reaper cycle completed: scanned={}, reclaimed={}, deadLettered={}",
                scannedCount, reclaimedCount, deadLetteredCount);

        if (eventBroadcaster != null && (reclaimedCount > 0 || deadLetteredCount > 0)) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("scannedCount", scannedCount);
            payload.put("reclaimedCount", reclaimedCount);
            payload.put("deadLetteredCount", deadLetteredCount);
            payload.put("timestamp", now.toString());
            eventBroadcaster.broadcast("OUTBOX_LEASE_RECLAIMED", payload);
        }

        return new OutboxLeaseReclaimResult(scannedCount, reclaimedCount, deadLetteredCount, now);
    }
}
