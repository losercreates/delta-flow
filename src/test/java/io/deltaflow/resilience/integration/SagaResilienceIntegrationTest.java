package io.deltaflow.resilience.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.resilience.ResiliencePolicyRegistry;
import io.deltaflow.saga.builder.SagaBuilder;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.exception.SagaCompensatedException;
import io.deltaflow.saga.service.SagaCoordinator;
import io.deltaflow.saga.service.SagaJournalStore;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaResilienceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaCoordinator coordinator;

    @Autowired
    private SagaJournalStore journalStore;

    @Autowired
    private ResiliencePolicyRegistry resiliencePolicyRegistry;

    record ResilienceTestRequest(String accountId, long amount) {}

    @Test
    @DisplayName("Should successfully retry transient failure and complete saga within max attempts")
    void testTransientFailureRetrySuccess() {
        String retryName = "transient-success-retry";
        resiliencePolicyRegistry.getOrCreateRetry(retryName, RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(20))
                .build());

        AtomicInteger callCount = new AtomicInteger(0);

        SagaDefinition<ResilienceTestRequest, Map<String, Object>> saga = SagaBuilder.<ResilienceTestRequest, Map<String, Object>>named("transient-retry-saga")
                .inputType(ResilienceTestRequest.class)
                .step("step-1")
                    .action((ctx, req) -> {
                        int attempt = callCount.incrementAndGet();
                        if (attempt < 3) {
                            throw new RuntimeException("Transient 503 Service Unavailable, attempt: " + attempt);
                        }
                        return Map.of("status", "SUCCESS", "attempts", attempt);
                    })
                    .withRetry(retryName)
                .onCompletion((ctx, req) -> Map.of("finalStatus", "COMPLETED"))
                .build();

        UUID sagaId = UUID.randomUUID();
        String idempotencyKey = "retry-success-" + sagaId;

        Map<String, Object> result = coordinator.executeSaga(saga, sagaId, "default", idempotencyKey, new ResilienceTestRequest("acc-1", 100));

        assertThat(result).isNotNull();
        assertThat(result.get("finalStatus")).isEqualTo("COMPLETED");
        assertThat(callCount.get()).isEqualTo(3);

        SagaInstance instance = journalStore.findSaga(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("COMPLETED");
        List<SagaStep> steps = journalStore.getStepsForSaga(sagaId);
        assertThat(steps.get(0).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Should exhaust retries and initiate backward compensation")
    void testExhaustedRetriesTriggerCompensation() {
        String retryName = "exhausted-retry";
        resiliencePolicyRegistry.getOrCreateRetry(retryName, RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(20))
                .build());

        AtomicInteger step2Calls = new AtomicInteger(0);
        AtomicBoolean step1Compensated = new AtomicBoolean(false);

        SagaDefinition<ResilienceTestRequest, Map<String, Object>> saga = SagaBuilder.<ResilienceTestRequest, Map<String, Object>>named("exhausted-retry-saga")
                .inputType(ResilienceTestRequest.class)
                .step("step-1")
                    .action((ctx, req) -> Map.of("step1", "done"))
                    .compensate((ctx, req) -> step1Compensated.set(true))
                .step("step-2")
                    .action((ctx, req) -> {
                        step2Calls.incrementAndGet();
                        throw new RuntimeException("Persistent 500 downstream error");
                    })
                    .withRetry(retryName)
                .build();

        UUID sagaId = UUID.randomUUID();
        String idempotencyKey = "retry-exhausted-" + sagaId;

        assertThatThrownBy(() -> coordinator.executeSaga(saga, sagaId, "default", idempotencyKey, new ResilienceTestRequest("acc-2", 200)))
                .isInstanceOf(SagaCompensatedException.class);

        assertThat(step2Calls.get()).isEqualTo(3);
        assertThat(step1Compensated.get()).isTrue();

        SagaInstance instance = journalStore.findSaga(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("COMPENSATED");
        List<SagaStep> steps = journalStore.getStepsForSaga(sagaId);
        assertThat(steps.get(0).getStatus()).isEqualTo("COMPENSATED");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Should trip circuit breaker from CLOSED to OPEN and fast-fail subsequent executions")
    void testCircuitBreakerTrippingAndFastFailure() {
        String cbName = "failing-service-cb";
        CircuitBreaker cb = resiliencePolicyRegistry.getOrCreateCircuitBreaker(cbName, CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());

        AtomicInteger actionInvocations = new AtomicInteger(0);
        AtomicBoolean step1Compensated = new AtomicBoolean(false);

        SagaDefinition<ResilienceTestRequest, Map<String, Object>> saga = SagaBuilder.<ResilienceTestRequest, Map<String, Object>>named("circuit-breaker-saga")
                .inputType(ResilienceTestRequest.class)
                .step("step-1")
                    .action((ctx, req) -> Map.of("step1", "ok"))
                    .compensate((ctx, req) -> step1Compensated.set(true))
                .step("step-2")
                    .action((ctx, req) -> {
                        actionInvocations.incrementAndGet();
                        throw new RuntimeException("Remote endpoint down");
                    })
                    .withCircuitBreaker(cbName)
                .build();

        // 1st failure
        UUID sagaId1 = UUID.randomUUID();
        assertThatThrownBy(() -> coordinator.executeSaga(saga, sagaId1, "default", "cb-1-" + sagaId1, new ResilienceTestRequest("acc-3", 300)))
                .isInstanceOf(SagaCompensatedException.class);
        assertThat(actionInvocations.get()).isEqualTo(1);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 2nd failure -> Trips circuit to OPEN
        UUID sagaId2 = UUID.randomUUID();
        assertThatThrownBy(() -> coordinator.executeSaga(saga, sagaId2, "default", "cb-2-" + sagaId2, new ResilienceTestRequest("acc-3", 300)))
                .isInstanceOf(SagaCompensatedException.class);
        assertThat(actionInvocations.get()).isEqualTo(2);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 3rd invocation: Circuit is OPEN -> Fast-fails with CallNotPermittedException without executing step action!
        step1Compensated.set(false);
        UUID sagaId3 = UUID.randomUUID();
        assertThatThrownBy(() -> coordinator.executeSaga(saga, sagaId3, "default", "cb-3-" + sagaId3, new ResilienceTestRequest("acc-3", 300)))
                .isInstanceOf(SagaCompensatedException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);

        // Verify action was NOT called again (still 2)
        assertThat(actionInvocations.get()).isEqualTo(2);
        // Verify preceding step was compensated
        assertThat(step1Compensated.get()).isTrue();

        SagaInstance instance3 = journalStore.findSaga(sagaId3).orElseThrow();
        assertThat(instance3.getStatus()).isEqualTo("COMPENSATED");
        List<SagaStep> steps3 = journalStore.getStepsForSaga(sagaId3);
        assertThat(steps3.get(0).getStatus()).isEqualTo("COMPENSATED");
        assertThat(steps3.get(1).getStatus()).isEqualTo("FAILED");
    }
}
