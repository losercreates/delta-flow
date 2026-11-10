package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.api.dto.SagaInstanceDto;
import io.deltaflow.saga.api.dto.SagaStepDto;
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

public class SagaQuickstartValidationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Quickstart End-to-End Validation: Scenario 1, Scenario 2, and Scenario 3")
    void testQuickstartScenarios() {
        String idempotencyKey = "transfer_quickstart_" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                "acc_1001",
                "acc_2002",
                500.00,
                "USD"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Scenario 1: Initial Transfer (Direct Execution)
        ResponseEntity<TransferResponseDto> response1 = restTemplate.postForEntity(
                "/api/v1/transfers",
                new HttpEntity<>(request, headers),
                TransferResponseDto.class
        );

        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response1.getHeaders().getFirst("Idempotency-Status")).isEqualTo("DIRECT");
        TransferResponseDto body1 = response1.getBody();
        assertThat(body1).isNotNull();
        assertThat(body1.status()).isEqualTo("COMPLETED");
        assertThat(body1.amount()).isEqualTo(500.00);
        assertThat(body1.currency()).isEqualTo("USD");
        assertThat(body1.stepsCompleted()).isEqualTo(3);
        UUID sagaId = body1.sagaId();

        // Scenario 2: Identical Replay (Served from Cache)
        ResponseEntity<TransferResponseDto> response2 = restTemplate.postForEntity(
                "/api/v1/transfers",
                new HttpEntity<>(request, headers),
                TransferResponseDto.class
        );

        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response2.getHeaders().getFirst("Idempotency-Status")).isEqualTo("CACHED");
        TransferResponseDto body2 = response2.getBody();
        assertThat(body2).isNotNull();
        assertThat(body2.sagaId()).isEqualTo(sagaId);
        assertThat(body2.amount()).isEqualTo(500.00);

        // Scenario 3: Inspect Saga Instance
        ResponseEntity<SagaInstanceDto> sagaResponse = restTemplate.getForEntity(
                "/api/v1/sagas/" + sagaId,
                SagaInstanceDto.class
        );
        assertThat(sagaResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sagaResponse.getBody()).isNotNull();
        assertThat(sagaResponse.getBody().status()).isEqualTo("COMPLETED");
        assertThat(sagaResponse.getBody().currentStep()).isEqualTo(2);

        // Scenario 3 (cont): Inspect Saga Steps
        ResponseEntity<List<SagaStepDto>> stepsResponse = restTemplate.exchange(
                "/api/v1/sagas/" + sagaId + "/steps",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(stepsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SagaStepDto> steps = stepsResponse.getBody();
        assertThat(steps).isNotNull();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).status()).isEqualTo("COMPLETED");
        assertThat(steps.get(1).status()).isEqualTo("COMPLETED");
        assertThat(steps.get(2).status()).isEqualTo("COMPLETED");
    }
}

