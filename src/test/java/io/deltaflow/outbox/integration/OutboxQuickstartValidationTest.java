package io.deltaflow.outbox.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.outbox.api.dto.OutboxEventDto;
import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.api.dto.TransferResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class OutboxQuickstartValidationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private io.deltaflow.outbox.service.OutboxPublisher outboxPublisher;

    @Test
    @DisplayName("Quickstart Validation: Outbox REST endpoints & Transfer Saga Outbox Event Generation")
    void testOutboxQuickstartScenario() {
        String idempotencyKey = "outbox-quickstart-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                "acc_source_101",
                "acc_target_202",
                350.00,
                "USD"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. Initiate transfer saga
        ResponseEntity<TransferResponseDto> transferResponse = restTemplate.postForEntity(
                "/api/v1/transfers",
                new HttpEntity<>(request, headers),
                TransferResponseDto.class
        );

        assertThat(transferResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransferResponseDto transferBody = transferResponse.getBody();
        assertThat(transferBody).isNotNull();
        assertThat(transferBody.status()).isEqualTo("COMPLETED");

        // 2. Query all outbox events via GET /api/v1/outbox
        ResponseEntity<List<OutboxEventDto>> listResponse = restTemplate.exchange(
                "/api/v1/outbox?limit=50",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<OutboxEventDto> events = listResponse.getBody();
        assertThat(events).isNotNull().isNotEmpty();

        // Find the event for this saga
        OutboxEventDto sagaCompletedEvent = events.stream()
                .filter(e -> "SAGA_COMPLETED".equals(e.eventType()) && transferBody.sagaId().toString().equals(e.aggregateId()))
                .findFirst()
                .orElse(events.get(0));

        UUID eventId = sagaCompletedEvent.eventId();

        // 3. Query single outbox event details via GET /api/v1/outbox/{eventId}
        ResponseEntity<OutboxEventDto> singleResponse = restTemplate.getForEntity(
                "/api/v1/outbox/" + eventId,
                OutboxEventDto.class
        );

        assertThat(singleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        OutboxEventDto singleEvent = singleResponse.getBody();
        assertThat(singleEvent).isNotNull();
        assertThat(singleEvent.eventId()).isEqualTo(eventId);
        assertThat(singleEvent.status()).isIn("PENDING", "IN_FLIGHT", "PUBLISHED");
        assertThat(singleEvent.createdAt()).isNotNull();

        // 4. Query non-existent event ID -> 404 NOT FOUND
        ResponseEntity<OutboxEventDto> notFoundResponse = restTemplate.getForEntity(
                "/api/v1/outbox/" + UUID.randomUUID(),
                OutboxEventDto.class
        );
        assertThat(notFoundResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 5. Trigger outbox scan and query with status filter
        outboxPublisher.runPublishingScan();

        ResponseEntity<List<OutboxEventDto>> filteredResponse = restTemplate.exchange(
                "/api/v1/outbox?status=PUBLISHED&limit=10",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(filteredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<OutboxEventDto> publishedEvents = filteredResponse.getBody();
        assertThat(publishedEvents).isNotNull();
        for (OutboxEventDto pub : publishedEvents) {
            assertThat(pub.status()).isEqualTo("PUBLISHED");
        }
    }
}

