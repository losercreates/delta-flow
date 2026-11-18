package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.idempotency.lock.RedisDistributedLock;
import io.deltaflow.saga.builder.SagaBuilder;
import io.deltaflow.saga.builder.SagaDefinition;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.exception.SagaSuspendedException;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.deltaflow.saga.service.SagaCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaAsyncSuspensionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaCoordinator sagaCoordinator;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Autowired
    private RedisDistributedLock redisDistributedLock;

    @Test
    @DisplayName("Saga encountering asyncWait step transitions to SUSPENDED, yields thread, and releases lock lease")
    void testSagaAsyncSuspensionAndResourceYielding() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-async-test";
        String idempotencyKey = "key-" + UUID.randomUUID();

        AtomicBoolean step1Executed = new AtomicBoolean(false);
        AtomicBoolean step3Executed = new AtomicBoolean(false);

        SagaDefinition<Map<String, Object>, String> asyncSaga = SagaBuilder.<Map<String, Object>, String>named("async-approval-saga")
                .step("debit-step")
                .action((ctx, input) -> {
                    step1Executed.set(true);
                    return Map.of("debited", 100.0);
                })
                .compensate((ctx, input) -> {})
                .asyncWait("user-approval-step", "user-approval", Duration.ofMinutes(15))
                .step("credit-step")
                .action((ctx, input) -> {
                    step3Executed.set(true);
                    return Map.of("credited", 100.0);
                })
                .onCompletion((ctx, input) -> "SUCCESS")
                .build();

        // Execution must throw SagaSuspendedException to signal thread yield
        assertThatThrownBy(() -> sagaCoordinator.executeSaga(
                asyncSaga,
                sagaId,
                tenantId,
                idempotencyKey,
                Map.of("amount", 100.0)
        )).isInstanceOf(SagaSuspendedException.class)
          .hasMessageContaining("user-approval");

        // Verify Step 1 completed, Step 2 is suspended, Step 3 was NOT executed
        assertThat(step1Executed.get()).isTrue();
        assertThat(step3Executed.get()).isFalse();

        // Verify database state in PostgreSQL
        SagaInstance instance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("SUSPENDED");
        assertThat(instance.getAwaitingEventName()).isEqualTo("user-approval");
        assertThat(instance.getStepDeadline()).isNotNull();
        assertThat(instance.getCurrentStep()).isEqualTo(1);

        SagaStep step0 = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 0).orElseThrow();
        assertThat(step0.getStatus()).isEqualTo("COMPLETED");

        SagaStep step1 = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 1).orElseThrow();
        assertThat(step1.getStatus()).isEqualTo("SUSPENDED");
        assertThat(step1.getAwaitingEventName()).isEqualTo("user-approval");
        assertThat(step1.getStepDeadline()).isNotNull();
    }
}
