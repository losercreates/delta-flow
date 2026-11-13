package io.deltaflow.outbox.service;

import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final AuditLogger auditLogger;
    private final TransactionTemplate transactionTemplate;

    @Value("${deltaflow.outbox.enabled:true}")
    private boolean enabled;

    @Value("${deltaflow.outbox.batch-size:50}")
    private int batchSize;

    @Value("${deltaflow.outbox.max-retries:5}")
    private int maxRetries;

    public OutboxPublisher(
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

    @Scheduled(fixedDelayString = "${deltaflow.outbox.poll-interval:1000}")
    public void scheduledPublish() {
        if (!enabled) {
            return;
        }
        runPublishingScan();
    }

    /**
     * Executes outbox relay cycle:
     * 1. Claims pending events in an atomic transaction using FOR UPDATE SKIP LOCKED.
     * 2. Dispatches events via DomainEventPublisher outside of database lock scope.
     * 3. Transitions status to PUBLISHED or DEAD_LETTER upon max retry breach.
     */
    public int runPublishingScan() {
        List<OutboxEvent> claimedEvents = transactionTemplate.execute(status -> {
            List<OutboxEvent> pending = outboxEventRepository.findPendingForUpdate(PageRequest.of(0, batchSize));
            if (pending.isEmpty()) {
                return Collections.emptyList();
            }
            List<OutboxEvent> claimed = new ArrayList<>(pending.size());
            for (OutboxEvent event : pending) {
                event.setStatus(OutboxStatus.IN_FLIGHT.name());
                claimed.add(outboxEventRepository.save(event));
            }
            return claimed;
        });

        if (claimedEvents == null || claimedEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : claimedEvents) {
            try {
                domainEventPublisher.publish(event);

                transactionTemplate.executeWithoutResult(status -> {
                    event.setStatus(OutboxStatus.PUBLISHED.name());
                    event.setPublishedAt(Instant.now());
                    outboxEventRepository.save(event);
                });

                Map<String, Object> details = new HashMap<>();
                details.put("eventId", event.getEventId().toString());
                details.put("eventType", event.getEventType());
                details.put("aggregateType", event.getAggregateType());
                details.put("aggregateId", event.getAggregateId());
                details.put("status", "PUBLISHED");
                auditLogger.logEvent("OUTBOX_PUBLISHED", "system", event.getAggregateId(), details);

                publishedCount++;
            } catch (Exception ex) {
                log.error("Failed to publish outbox event [{}]: {}", event.getEventId(), ex.getMessage(), ex);

                transactionTemplate.executeWithoutResult(status -> {
                    int retries = event.getRetryCount() + 1;
                    event.setRetryCount(retries);
                    String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    event.setErrorDetails(errorMsg);

                    if (retries >= maxRetries) {
                        event.setStatus(OutboxStatus.DEAD_LETTER.name());
                        Map<String, Object> dlDetails = new HashMap<>();
                        dlDetails.put("eventId", event.getEventId().toString());
                        dlDetails.put("retryCount", retries);
                        dlDetails.put("errorDetails", errorMsg);
                        auditLogger.logEvent("OUTBOX_DEAD_LETTER", "system", event.getAggregateId(), dlDetails);
                    } else {
                        event.setStatus(OutboxStatus.PENDING.name());
                        Map<String, Object> retryDetails = new HashMap<>();
                        retryDetails.put("eventId", event.getEventId().toString());
                        retryDetails.put("retryCount", retries);
                        retryDetails.put("errorDetails", errorMsg);
                        auditLogger.logEvent("OUTBOX_RETRY", "system", event.getAggregateId(), retryDetails);
                    }
                    outboxEventRepository.save(event);
                });
            }
        }

        return publishedCount;
    }
}

