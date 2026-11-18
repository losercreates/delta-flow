package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.builder.SagaBuilder;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.exception.SagaSuspendedException;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.deltaflow.saga.service.SagaCoordinator;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaWebhookResumptionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaCoordinator sagaCoordinator;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Autowired
    private WebhookSecurityService securityService;

    @Test
    @DisplayName("Suspended saga resumes on valid webhook, re-acquires lock, and completes remaining forward steps")
    void testSuspendedSagaResumptionForwardCompletion() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-webhook-resume";
        String idempotencyKey = "key-" + UUID.randomUUID();

        AtomicBoolean step1Executed = new AtomicBoolean(false);
        AtomicBoolean step3Executed = new AtomicBoolean(false);

        SagaDefinition<Map<String, Object>, String> sagaDef = SagaBuilder.<Map<String, Object>, String>named("3ds-resumption-saga")
                .step("debit-card")
                .action((ctx, input) -> {
                    step1Executed.set(true);
                    return Map.of("debited", 250.0);
                })
                .compensate((ctx, input) -> {})
                .asyncWait("challenge-otp-step", "3ds-callback", Duration.ofMinutes(10))
                .step("capture-settlement")
                .action((ctx, input) -> {
                    step3Executed.set(true);
                    return Map.of("captured", true);
                })
                .onCompletion((ctx, input) -> "PAYMENT_SETTLED")
                .build();

        sagaCoordinator.registerDefinition(sagaDef);

        // 1. Initial execution suspends
        assertThatThrownBy(() -> sagaCoordinator.executeSaga(
                sagaDef,
                sagaId,
                tenantId,
                idempotencyKey,
                Map.of("amount", 250.0)
        )).isInstanceOf(SagaSuspendedException.class);

        assertThat(step1Executed.get()).isTrue();
        assertThat(step3Executed.get()).isFalse();

        SagaInstance suspendedInstance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(suspendedInstance.getStatus()).isEqualTo("SUSPENDED");
        assertThat(suspendedInstance.getAwaitingEventName()).isEqualTo("3ds-callback");

        // 2. Deliver valid webhook callback
        String url = "/api/v1/sagas/" + sagaId + "/events/3ds-callback";
        long timestamp = Instant.now().toEpochMilli();
        String webhookBody = "{\"status\":\"APPROVED\",\"authCode\":\"AUTH-999\",\"cavv\":\"test-cavv\"}";
        String signature = securityService.computeHmacSha256(timestamp + "." + webhookBody, securityService.getDefaultSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", signature);
        headers.set("X-Webhook-Timestamp", String.valueOf(timestamp));
        headers.set("X-Tenant-ID", tenantId);
        headers.set("Idempotency-Key", "webhook-delivery-" + sagaId);

        HttpEntity<String> entity = new HttpEntity<>(webhookBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "RESUMED");
        assertThat(response.getBody()).containsEntry("sagaId", sagaId.toString());

        // 3. Verify step 3 executed and saga completed
        assertThat(step3Executed.get()).isTrue();

        SagaInstance completedInstance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(completedInstance.getStatus()).isEqualTo("COMPLETED");

        SagaStep step1 = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 1).orElseThrow();
        assertThat(step1.getStatus()).isEqualTo("COMPLETED");

        SagaStep step2 = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 2).orElseThrow();
        assertThat(step2.getStatus()).isEqualTo("COMPLETED");

        // 4. Test idempotent replay of same webhook delivery
        ResponseEntity<Map> replayResponse = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayResponse.getBody()).containsEntry("status", "REPLAYED");
    }

    @Test
    @DisplayName("Webhook with REJECTED payload triggers backward compensation for suspended saga")
    void testWebhookRejectionTriggersBackwardCompensation() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-webhook-reject";
        String idempotencyKey = "key-" + UUID.randomUUID();

        AtomicBoolean compensated = new AtomicBoolean(false);

        SagaDefinition<Map<String, Object>, String> sagaDef = SagaBuilder.<Map<String, Object>, String>named("fraud-check-saga")
                .step("reserve-balance")
                .action((ctx, input) -> Map.of("reserved", 150.0))
                .compensate((ctx, input) -> compensated.set(true))
                .asyncWait("fraud-decision-step", "fraud-decision", Duration.ofMinutes(5))
                .step("finalize-order")
                .action((ctx, input) -> Map.of("orderId", "ORD-123"))
                .onCompletion((ctx, input) -> "ORDER_COMPLETED")
                .build();

        sagaCoordinator.registerDefinition(sagaDef);

        assertThatThrownBy(() -> sagaCoordinator.executeSaga(
                sagaDef,
                sagaId,
                tenantId,
                idempotencyKey,
                Map.of("amount", 150.0)
        )).isInstanceOf(SagaSuspendedException.class);

        // Deliver REJECTED webhook
        String url = "/api/v1/sagas/" + sagaId + "/events/fraud-decision";
        long timestamp = Instant.now().toEpochMilli();
        String webhookBody = "{\"status\":\"REJECTED\",\"reason\":\"High fraud risk detected\"}";
        String signature = securityService.computeHmacSha256(timestamp + "." + webhookBody, securityService.getDefaultSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", signature);
        headers.set("X-Webhook-Timestamp", String.valueOf(timestamp));
        headers.set("X-Tenant-ID", tenantId);

        HttpEntity<String> entity = new HttpEntity<>(webhookBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // Backward compensation must have executed
        assertThat(compensated.get()).isTrue();

        SagaInstance instance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("COMPENSATED");
    }
}
