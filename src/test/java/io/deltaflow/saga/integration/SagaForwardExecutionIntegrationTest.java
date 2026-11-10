package io.deltaflow.saga.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.api.dto.SagaInstanceDto;
import io.deltaflow.saga.api.dto.SagaStepDto;
import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.api.dto.TransferResponseDto;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SagaForwardExecutionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Forward saga execution completes 3/3 steps and records WAL journal entries")
    void testForward3StepExecution() {
        String idempotencyKey = "transfer-test-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                "acc_source_100",
                "acc_target_200",
                500.00,
                "USD"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Tenant-Id", "tenant-transfer-test");
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<TransferRequestDto> entity = new HttpEntity<>(request, headers);

        // 1. Submit transfer
        ResponseEntity<TransferResponseDto> response = restTemplate.postForEntity(
                "/api/v1/transfers",
                entity,
                TransferResponseDto.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("Idempotency-Status")).isEqualTo("DIRECT");

        TransferResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo("COMPLETED");
        assertThat(body.amount()).isEqualTo(500.00);
        assertThat(body.currency()).isEqualTo("USD");
        assertThat(body.stepsCompleted()).isEqualTo(3);
        assertThat(body.sagaId()).isNotNull();

        UUID sagaId = body.sagaId();

        // 2. Verify Database State
        SagaInstance instance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("COMPLETED");
        assertThat(instance.getCurrentStep()).isEqualTo(2); // 0, 1, 2 = 3 steps
        assertThat(instance.getTenantId()).isEqualTo("tenant-transfer-test");
        assertThat(instance.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(instance.getResultPayload()).contains("COMPLETED");

        List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(steps).hasSize(3);

        // Step 0: debit-step
        assertThat(steps.get(0).getStepName()).isEqualTo("debit-step");
        assertThat(steps.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(steps.get(0).getResponsePayload()).contains("deb_");

        // Step 1: credit-step
        assertThat(steps.get(1).getStepName()).isEqualTo("credit-step");
        assertThat(steps.get(1).getStatus()).isEqualTo("COMPLETED");
        assertThat(steps.get(1).getResponsePayload()).contains("crd_");

        // Step 2: ledger-step
        assertThat(steps.get(2).getStepName()).isEqualTo("ledger-step");
        assertThat(steps.get(2).getStatus()).isEqualTo("COMPLETED");
        assertThat(steps.get(2).getResponsePayload()).contains("RECORDED");

        // 3. Verify Inspector Endpoints
        ResponseEntity<SagaInstanceDto> sagaQueryResponse = restTemplate.getForEntity(
                "/api/v1/sagas/" + sagaId,
                SagaInstanceDto.class
        );
        assertThat(sagaQueryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sagaQueryResponse.getBody()).isNotNull();
        assertThat(sagaQueryResponse.getBody().status()).isEqualTo("COMPLETED");

        ResponseEntity<List<SagaStepDto>> stepsQueryResponse = restTemplate.exchange(
                "/api/v1/sagas/" + sagaId + "/steps",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(stepsQueryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stepsQueryResponse.getBody()).hasSize(3);
        assertThat(stepsQueryResponse.getBody().get(0).stepName()).isEqualTo("debit-step");
        assertThat(stepsQueryResponse.getBody().get(1).stepName()).isEqualTo("credit-step");
        assertThat(stepsQueryResponse.getBody().get(2).stepName()).isEqualTo("ledger-step");
    }
}

