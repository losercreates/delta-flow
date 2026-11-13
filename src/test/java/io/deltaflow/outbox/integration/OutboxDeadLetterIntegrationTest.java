package io.deltaflow.outbox.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.deltaflow.outbox.service.DomainEventPublisher;
import io.deltaflow.outbox.service.OutboxPublisher;
import io.deltaflow.outbox.service.OutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDeadLetterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DomainEventPublisher domainEventPublisher;

    @AfterEach
    void tearDown() {
        if (domainEventPublisher instanceof DomainEventPublisher.DefaultDomainEventPublisher def) {
            def.clear();
        }
    }

    @Test
    @DisplayName("Should quarantine unpublishable poison pill event to DEAD_LETTER after retry exhaustion without blocking valid events")
    void testPoisonPillQuarantinedWithoutHeadOfLineBlocking() {
        if (domainEventPublisher instanceof DomainEventPublisher.DefaultDomainEventPublisher def) {
            def.setInterceptor(ev -> {
                if ("POISON_PILL".equals(ev.getEventType())) {
                    throw new RuntimeException("Broker rejected poison payload: unparsable schema");
                }
            });
        }

        // Record a poison pill event first
        OutboxEvent poisonPill = outboxService.recordEvent(
                "PAYMENT", "pay-poison-1", "POISON_PILL",
                Map.of("corrupt", true), "default", "key-poison-1"
        );

        // Record a healthy valid event second
        OutboxEvent validEvent = outboxService.recordEvent(
                "PAYMENT", "pay-valid-2", "PAYMENT_PROCESSED",
                Map.of("amount", 250), "default", "key-valid-2"
        );

        // Run 5 publishing scans (default maxRetries is 5)
        for (int i = 0; i < 5; i++) {
            outboxPublisher.runPublishingScan();
        }

        // Verify healthy valid event was published successfully (no head-of-line blocking)
        OutboxEvent refreshedValid = outboxEventRepository.findById(validEvent.getEventId()).orElseThrow();
        assertThat(refreshedValid.getStatus()).isEqualTo(OutboxStatus.PUBLISHED.name());
        assertThat(refreshedValid.getPublishedAt()).isNotNull();

        // Verify poison pill transitioned to DEAD_LETTER
        OutboxEvent refreshedPoison = outboxEventRepository.findById(poisonPill.getEventId()).orElseThrow();
        assertThat(refreshedPoison.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTER.name());
        assertThat(refreshedPoison.getRetryCount()).isEqualTo(5);
        assertThat(refreshedPoison.getErrorDetails()).contains("Broker rejected poison payload: unparsable schema");

        // Subsequent publishing scan should find NO pending events
        int claimed = outboxPublisher.runPublishingScan();
        assertThat(claimed).isEqualTo(0);
    }
}
