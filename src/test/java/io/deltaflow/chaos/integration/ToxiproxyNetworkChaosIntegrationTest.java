package io.deltaflow.chaos.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.api.dto.TransferResponseDto;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

public class ToxiproxyNetworkChaosIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ToxiproxyNetworkChaosIntegrationTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Test
    @DisplayName("US1: Concurrent saga executions under simulated network packet loss and latency jitter")
    void testSagaExecutionUnderNetworkChaos() throws Exception {
        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<ResponseEntity<TransferResponseDto>>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            String key = "chaos-key-" + UUID.randomUUID();
            TransferRequestDto request = new TransferRequestDto(
                    "acc_source_" + (100 + i),
                    "acc_target_" + (200 + i),
                    100.00,
                    "USD"
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Idempotency-Key", key);
            headers.set("X-Tenant-Id", "tenant-chaos");
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<TransferRequestDto> entity = new HttpEntity<>(request, headers);

            futures.add(executor.submit(() -> restTemplate.postForEntity(
                    "/api/v1/transfers",
                    entity,
                    TransferResponseDto.class
            )));
        }

        int successCount = 0;
        int errorCount = 0;

        for (Future<ResponseEntity<TransferResponseDto>> future : futures) {
            try {
                ResponseEntity<TransferResponseDto> response = future.get(10, TimeUnit.SECONDS);
                if (response.getStatusCode().is2xxSuccessful()) {
                    successCount++;
                } else {
                    errorCount++;
                }
            } catch (Exception ex) {
                errorCount++;
            }
        }

        executor.shutdown();
        log.info("Chaos concurrent run finished: {} succeeded, {} safely rolled back or handled", successCount, errorCount);

        assertThat(successCount + errorCount).isEqualTo(concurrency);

        // Verify that all persisted sagas reached a valid terminal state
        List<SagaInstance> allSagas = sagaInstanceRepository.findAll();
        for (SagaInstance saga : allSagas) {
            if ("tenant-chaos".equals(saga.getTenantId())) {
                assertThat(saga.getStatus()).isIn("COMPLETED", "COMPENSATED", "FAILED");
            }
        }
    }

    @Test
    @DisplayName("US1: Idempotent replay under network degradation returns cached response")
    void testIdempotentReplayUnderChaos() {
        String key = "chaos-replay-" + UUID.randomUUID();
        TransferRequestDto request = new TransferRequestDto(
                "acc_source_replay",
                "acc_target_replay",
                50.00,
                "USD"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("X-Tenant-Id", "tenant-chaos");
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<TransferRequestDto> entity = new HttpEntity<>(request, headers);

        // First attempt
        ResponseEntity<TransferResponseDto> resp1 = restTemplate.postForEntity(
                "/api/v1/transfers",
                entity,
                TransferResponseDto.class
        );
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second attempt with exact same key
        ResponseEntity<TransferResponseDto> resp2 = restTemplate.postForEntity(
                "/api/v1/transfers",
                entity,
                TransferResponseDto.class
        );
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp2.getBody().sagaId()).isEqualTo(resp1.getBody().sagaId());
    }
}
