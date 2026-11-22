package io.deltaflow.cluster;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.cluster.domain.OutboxLeaseReclaimResult;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.deltaflow.outbox.service.OutboxLeaseRecoveryReaper;
import io.deltaflow.outbox.service.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("In-Flight Outbox Lease Recovery Integration Test")
class InFlightOutboxLeaseRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxLeaseRecoveryReaper outboxLeaseRecoveryReaper;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Should reclaim expired in-flight outbox event and reset to PENDING with retry increment")
    void shouldReclaimExpiredInFlightEvent() {
        // Given an event stuck in IN_FLIGHT past its lease expiration (e.g. crashed worker)
        UUID eventId = UUID.randomUUID();
        OutboxEvent strandedEvent = new OutboxEvent(
                eventId,
                "Transfer",
                "tx-test-reclaim-1",
                "TRANSFER_SUBMITTED",
                "{\"amount\":100.00}"
        );
        strandedEvent.setStatus(OutboxStatus.IN_FLIGHT.name());
        strandedEvent.setClaimedByWorker("crashed-worker-node-1");
        strandedEvent.setLeaseExpiresAt(Instant.now().minus(30, ChronoUnit.SECONDS)); // Expired 30s ago
        strandedEvent.setRetryCount(1);
        outboxEventRepository.saveAndFlush(strandedEvent);

        // When the recovery reaper executes a scan
        OutboxLeaseReclaimResult result = outboxLeaseRecoveryReaper.runReaperScan();

        // Then the event is reclaimed
        assertThat(result.scannedCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.reclaimedCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.deadLetteredCount()).isEqualTo(0);

        OutboxEvent recovered = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PENDING.name());
        assertThat(recovered.getRetryCount()).isEqualTo(2); // Incremented from 1 to 2
        assertThat(recovered.getLeaseExpiresAt()).isNull();
        assertThat(recovered.getClaimedByWorker()).isNull();

        // And subsequent outbox publisher cycle processes it to PUBLISHED
        int published = outboxPublisher.runPublishingScan();
        assertThat(published).isGreaterThanOrEqualTo(1);

        OutboxEvent publishedEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(publishedEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED.name());
    }

    @Test
    @DisplayName("Should route expired in-flight event to DEAD_LETTER when retry count exceeds threshold")
    void shouldRouteToDeadLetterWhenMaxRetriesExceeded() {
        // Given an in-flight event that has already exhausted max retries (maxRetries is 5)
        UUID eventId = UUID.randomUUID();
        OutboxEvent poisonEvent = new OutboxEvent(
                eventId,
                "Transfer",
                "tx-test-poison-1",
                "TRANSFER_SUBMITTED",
                "{\"amount\":999.00}"
        );
        poisonEvent.setStatus(OutboxStatus.IN_FLIGHT.name());
        poisonEvent.setClaimedByWorker("crashed-worker-node-2");
        poisonEvent.setLeaseExpiresAt(Instant.now().minus(10, ChronoUnit.SECONDS));
        poisonEvent.setRetryCount(5); // At max retries
        outboxEventRepository.saveAndFlush(poisonEvent);

        // When recovery reaper runs
        OutboxLeaseReclaimResult result = outboxLeaseRecoveryReaper.runReaperScan();

        // Then it transitions directly to DEAD_LETTER
        assertThat(result.deadLetteredCount()).isGreaterThanOrEqualTo(1);

        OutboxEvent deadLettered = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(deadLettered.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER.name());
        assertThat(deadLettered.getErrorDetails()).contains("Lease expired");
    }

    @Test
    @DisplayName("Should not reclaim in-flight events whose lease is still active")
    void shouldNotReclaimActiveInFlightEvent() {
        // Given an event actively IN_FLIGHT with a valid future lease expiration
        UUID eventId = UUID.randomUUID();
        OutboxEvent activeEvent = new OutboxEvent(
                eventId,
                "Transfer",
                "tx-test-active-1",
                "TRANSFER_SUBMITTED",
                "{\"amount\":50.00}"
        );
        activeEvent.setStatus(OutboxStatus.IN_FLIGHT.name());
        activeEvent.setClaimedByWorker("live-worker-node");
        activeEvent.setLeaseExpiresAt(Instant.now().plus(45, ChronoUnit.SECONDS)); // Active lease
        activeEvent.setRetryCount(0);
        outboxEventRepository.saveAndFlush(activeEvent);

        // When recovery reaper runs
        OutboxLeaseReclaimResult result = outboxLeaseRecoveryReaper.runReaperScan();

        // Then the event is untouched
        assertThat(result.reclaimedCount()).isEqualTo(0);
        OutboxEvent untouched = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(OutboxStatus.IN_FLIGHT.name());
        assertThat(untouched.getClaimedByWorker()).isEqualTo("live-worker-node");
    }
}
