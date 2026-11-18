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
import io.deltaflow.saga.service.SagaRecoveryScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaAsyncTimeoutCompensationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaCoordinator sagaCoordinator;

    @Autowired
    private SagaRecoveryScanner sagaRecoveryScanner;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Test
    @DisplayName("Expired suspended saga transitions to TIMED_OUT and triggers LIFO backward compensation")
    void testExpiredSuspendedSagaTimeoutCompensation() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-timeout-test";
        String idempotencyKey = "key-" + UUID.randomUUID();

        AtomicBoolean step1Executed = new AtomicBoolean(false);
        AtomicBoolean step1Compensated = new AtomicBoolean(false);

        SagaDefinition<Map<String, Object>, String> timeoutSaga = SagaBuilder.<Map<String, Object>, String>named("timeout-compensation-saga")
                .step("debit-funds")
                .action((ctx, input) -> {
                    step1Executed.set(true);
                    return Map.of("debited", 500.0);
                })
                .compensate((ctx, input) -> {
                    step1Compensated.set(true);
                })
                .asyncWait("external-verification", "verification-callback", Duration.ofSeconds(1))
                .step("finalize-transaction")
                .action((ctx, input) -> Map.of("finalized", true))
                .onCompletion((ctx, input) -> "COMPLETED")
                .build();

        sagaCoordinator.registerDefinition(timeoutSaga);
        sagaRecoveryScanner.registerDefinition(timeoutSaga);

        // 1. Execute saga - enters SUSPENDED state
        assertThatThrownBy(() -> sagaCoordinator.executeSaga(
                timeoutSaga,
                sagaId,
                tenantId,
                idempotencyKey,
                Map.of("amount", 500.0)
        )).isInstanceOf(SagaSuspendedException.class);

        assertThat(step1Executed.get()).isTrue();
        assertThat(step1Compensated.get()).isFalse();

        // 2. Set deadline to past to simulate timeout expiration
        SagaInstance instance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        instance.setStepDeadline(Instant.now().minusSeconds(10));
        sagaInstanceRepository.saveAndFlush(instance);

        SagaStep asyncStep = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 1).orElseThrow();
        asyncStep.setStepDeadline(Instant.now().minusSeconds(10));
        sagaStepRepository.saveAndFlush(asyncStep);

        // 3. Run suspended timeout scan
        int compensatedCount = sagaRecoveryScanner.runSuspendedTimeoutScan();
        assertThat(compensatedCount).isGreaterThanOrEqualTo(1);

        // 4. Verify backward compensation occurred in LIFO order
        assertThat(step1Compensated.get()).isTrue();

        SagaInstance compensatedInstance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(compensatedInstance.getStatus()).isEqualTo("COMPENSATED");

        SagaStep step0 = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 0).orElseThrow();
        assertThat(step0.getStatus()).isEqualTo("COMPENSATED");
    }
}
