package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.builder.SagaBuilder;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.exception.SagaSuspendedException;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.service.SagaCoordinator;
import io.deltaflow.saga.service.SagaRecoveryScanner;
import io.deltaflow.saga.webhook.WebhookSecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaZombieWebhookRejectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaCoordinator sagaCoordinator;

    @Autowired
    private SagaRecoveryScanner sagaRecoveryScanner;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private WebhookSecurityService securityService;

    @Test
    @DisplayName("Late-arriving zombie webhook for timed-out/compensated saga is rejected with HTTP 409 Conflict")
    void testZombieWebhookRejectedWithConflict() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-zombie-test";
        String idempotencyKey = "key-" + UUID.randomUUID();

        SagaDefinition<Map<String, Object>, String> sagaDef = SagaBuilder.<Map<String, Object>, String>named("zombie-rejection-saga")
                .step("initial-hold")
                .action((ctx, input) -> Map.of("held", 100.0))
                .compensate((ctx, input) -> {})
                .asyncWait("wait-for-callback", "zombie-event", Duration.ofSeconds(1))
                .step("final-release")
                .action((ctx, input) -> Map.of("released", true))
                .onCompletion((ctx, input) -> "DONE")
                .build();

        sagaCoordinator.registerDefinition(sagaDef);
        sagaRecoveryScanner.registerDefinition(sagaDef);

        // 1. Initial execution suspends
        assertThatThrownBy(() -> sagaCoordinator.executeSaga(
                sagaDef,
                sagaId,
                tenantId,
                idempotencyKey,
                Map.of("amount", 100.0)
        )).isInstanceOf(SagaSuspendedException.class);

        // 2. Backdate deadline and trigger timeout recovery
        SagaInstance instance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        instance.setStepDeadline(Instant.now().minusSeconds(30));
        sagaInstanceRepository.saveAndFlush(instance);

        sagaRecoveryScanner.runSuspendedTimeoutScan();

        SagaInstance compensated = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(compensated.getStatus()).isEqualTo("COMPENSATED");

        // 3. Late zombie webhook arrives
        String url = "/api/v1/sagas/" + sagaId + "/events/zombie-event";
        long timestamp = Instant.now().toEpochMilli();
        String webhookBody = "{\"status\":\"APPROVED\",\"authCode\":\"TOO-LATE\"}";
        String signature = securityService.computeHmacSha256(timestamp + "." + webhookBody, securityService.getDefaultSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", signature);
        headers.set("X-Webhook-Timestamp", String.valueOf(timestamp));
        headers.set("X-Tenant-ID", tenantId);

        HttpEntity<String> entity = new HttpEntity<>(webhookBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

        // 4. Must be rejected with HTTP 409 Conflict
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "CONFLICT");

        // 5. Database status remains COMPENSATED (fencing protection preserved)
        SagaInstance postWebhook = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(postWebhook.getStatus()).isEqualTo("COMPENSATED");
    }
}
