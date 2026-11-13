package io.deltaflow.outbox.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import io.deltaflow.outbox.service.DomainEventPublisher;
import io.deltaflow.outbox.service.OutboxPublisher;
import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.api.dto.TransferResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class OutboxPublishingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private DomainEventPublisher domainEventPublisher;

    @BeforeEach
    void setUp() {
        if (domainEventPublisher instanceof DomainEventPublisher.DefaultDomainEventPublisher def) {
            def.clear();
        }
    }

    @Test
    @DisplayName("Saga execution atomically persists outbox events and relay daemon dispatches them")
    void testAtomicOutboxPersistenceAndRelayDispatch() {
        String idempotencyKey = "outbox-test-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                "acc_outbox_source",
                "acc_outbox_target",
                300.00,
                "USD"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<TransferResponseDto> response = restTemplate.postForEntity(
                "/api/v1/transfers",
                new HttpEntity<>(request, headers),
                TransferResponseDto.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        UUID sagaId = response.getBody().sagaId();
        assertThat(sagaId).isNotNull();

        // 1. Verify outbox events were recorded for the saga
        List<OutboxEvent> events = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "SAGA_INSTANCE", sagaId.toString());
        assertThat(events).isNotEmpty();

        // 2. Trigger the outbox relay publisher scan
        int publishedCount = outboxPublisher.runPublishingScan();
        assertThat(publishedCount).isGreaterThanOrEqualTo(events.size());

        // 3. Verify all events transitioned to PUBLISHED with timestamps
        List<OutboxEvent> updatedEvents = outboxEventRepository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
                "SAGA_INSTANCE", sagaId.toString());
        for (OutboxEvent event : updatedEvents) {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED.name());
            assertThat(event.getPublishedAt()).isNotNull();
        }

        // 4. Verify dispatcher received the published events
        if (domainEventPublisher instanceof DomainEventPublisher.DefaultDomainEventPublisher def) {
            assertThat(def.getPublishedEvents()).extracting(OutboxEvent::getEventId)
                    .containsAll(updatedEvents.stream().map(OutboxEvent::getEventId).toList());
        }
    }
}

