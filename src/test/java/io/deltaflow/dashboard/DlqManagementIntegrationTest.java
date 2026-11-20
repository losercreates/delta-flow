package io.deltaflow.dashboard;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.dashboard.dto.DeadLetterOutboxEventDto;
import io.deltaflow.dashboard.dto.DlqReplayRequest;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DlqManagementIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("US4: GET /api/v1/dashboard/dlq and POST /api/v1/dashboard/dlq/{id}/replay")
    void testDlqListingAndReplay() {
        UUID eventId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();

        OutboxEvent poisonEvent = new OutboxEvent(
                eventId,
                "SAGA_INSTANCE",
                sagaId.toString(),
                "TRANSFER_SETTLED",
                "{\"amount\": 100.0}"
        );
        poisonEvent.setStatus("DEAD_LETTER");
        poisonEvent.setRetryCount(5);
        poisonEvent.setErrorDetails("Connection to Kafka broker timed out repeatedly");
        poisonEvent.setPartitionKey("tenant-dlq-test");
        outboxEventRepository.save(poisonEvent);

        // 1. Query DLQ list
        ResponseEntity<DeadLetterOutboxEventDto[]> listResp = restTemplate.getForEntity(
                "/api/v1/dashboard/dlq",
                DeadLetterOutboxEventDto[].class
        );

        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        DeadLetterOutboxEventDto[] dlqEvents = listResp.getBody();
        assertThat(dlqEvents).isNotNull();
        assertThat(dlqEvents).anyMatch(e -> eventId.equals(e.eventId()));

        // 2. Replay the DLQ event
        DlqReplayRequest replayReq = new DlqReplayRequest("ops-user-1", "Broker restored; replaying poison event");
        ResponseEntity<DeadLetterOutboxEventDto> replayResp = restTemplate.postForEntity(
                "/api/v1/dashboard/dlq/" + eventId + "/replay",
                replayReq,
                DeadLetterOutboxEventDto.class
        );

        assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayResp.getBody()).isNotNull();
        assertThat(replayResp.getBody().eventId()).isEqualTo(eventId);

        // 3. Verify in repository that it is reset to PENDING
        OutboxEvent updated = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("PENDING");
        assertThat(updated.getRetryCount()).isEqualTo(0);
        assertThat(updated.getErrorDetails()).isNull();
    }
}
