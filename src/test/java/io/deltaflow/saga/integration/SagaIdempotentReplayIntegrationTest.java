package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.api.dto.TransferResponseDto;
import io.deltaflow.saga.repository.SagaStepRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SagaIdempotentReplayIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Test
    @DisplayName("Duplicate request with same Idempotency-Key returns cached response without re-executing steps")
    void testDuplicateRequestReturnsCachedResponse() {
        String idempotencyKey = "replay-key-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                "acc_sender_1",
                "acc_receiver_2",
                300.00,
                "USD"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Tenant-Id", "tenant-replay-test");
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<TransferRequestDto> entity = new HttpEntity<>(request, headers);

        // 1. Initial execution
        ResponseEntity<TransferResponseDto> firstResponse = restTemplate.postForEntity(
                "/api/v1/transfers",
                entity,
                TransferResponseDto.class
        );

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(firstResponse.getHeaders().getFirst("Idempotency-Status")).isEqualTo("DIRECT");
        TransferResponseDto firstBody = firstResponse.getBody();
        assertThat(firstBody).isNotNull();
        UUID sagaId = firstBody.sagaId();

        int initialStepCount = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId).size();
        assertThat(initialStepCount).isEqualTo(3);

        // 2. Replay with identical payload and key
        ResponseEntity<TransferResponseDto> replayResponse = restTemplate.postForEntity(
                "/api/v1/transfers",
                entity,
                TransferResponseDto.class
        );

        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayResponse.getHeaders().getFirst("Idempotency-Status")).isEqualTo("CACHED");
        TransferResponseDto replayBody = replayResponse.getBody();
        assertThat(replayBody).isNotNull();
        assertThat(replayBody.sagaId()).isEqualTo(sagaId);
        assertThat(replayBody.transactionId()).isEqualTo(firstBody.transactionId());
        assertThat(replayBody.amount()).isEqualTo(300.00);

        // Step count must remain exactly 3 (no duplicate step executions)
        int stepCountAfterReplay = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId).size();
        assertThat(stepCountAfterReplay).isEqualTo(3);
    }

    @Test
    @DisplayName("Duplicate key with mismatched payload triggers HTTP 409 Conflict")
    void testTamperedPayloadReturnsConflict() {
        String idempotencyKey = "tamper-key-" + UUID.randomUUID();
        TransferRequestDto initialRequest = new TransferRequestDto(
                "acc_alice",
                "acc_bob",
                100.00,
                "USD"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Tenant-Id", "tenant-replay-test");
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. Execute initial request
        ResponseEntity<TransferResponseDto> initialResponse = restTemplate.postForEntity(
                "/api/v1/transfers",
                new HttpEntity<>(initialRequest, headers),
                TransferResponseDto.class
        );
        assertThat(initialResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 2. Submit second request with altered amount
        TransferRequestDto tamperedRequest = new TransferRequestDto(
                "acc_alice",
                "acc_bob",
                9999.00,
                "USD"
        );

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> conflictResponse = restTemplate.postForEntity(
                "/api/v1/transfers",
                new HttpEntity<>(tamperedRequest, headers),
                Map.class
        );

        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResponse.getBody()).isNotNull();
        assertThat(conflictResponse.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
    }
}

