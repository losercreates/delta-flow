package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.deltaflow.saga.service.SagaCoordinator;
import io.deltaflow.saga.service.SagaJournalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SagaZombieFencingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaCoordinator sagaCoordinator;

    @Autowired
    private SagaJournalStore sagaJournalStore;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Test
    @DisplayName("Zombie step completion rejected when saga has transitioned to COMPENSATED")
    void testZombieStepRejectedWhenSagaCompensated() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-zombie-test";
        String idempotencyKey = "zombie-idemp-" + UUID.randomUUID();

        // 1. Create a saga that has already reached COMPENSATED state
        SagaInstance instance = new SagaInstance(
                sagaId,
                tenantId,
                idempotencyKey,
                "account-transfer",
                "{\"amount\":100.00}"
        );
        instance.setStatus("COMPENSATED");
        sagaInstanceRepository.saveAndFlush(instance);

        // 2. Step 1 was in progress before rollback occurred
        SagaStep step1 = new SagaStep(UUID.randomUUID(), sagaId, "credit-step", 1, "EXECUTING");
        step1.setFencingToken(1L);
        sagaStepRepository.saveAndFlush(step1);

        // 3. Late arriving zombie response attempts to validate / complete
        assertThatThrownBy(() -> sagaCoordinator.validateStepFencing(sagaId, 1, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZOMBIE_STEP_REJECTED")
                .hasMessageContaining("COMPENSATED");

        // 4. Verify step status was NOT updated to COMPLETED
        SagaStep stepAfter = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 1).orElseThrow();
        assertThat(stepAfter.getStatus()).isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("Zombie step completion rejected when fencing token is stale")
    void testStaleFencingTokenRejected() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-zombie-test";
        String idempotencyKey = "stale-idemp-" + UUID.randomUUID();

        // 1. Create active executing saga
        SagaInstance instance = new SagaInstance(
                sagaId,
                tenantId,
                idempotencyKey,
                "account-transfer",
                "{\"amount\":100.00}"
        );
        instance.setStatus("EXECUTING");
        sagaInstanceRepository.saveAndFlush(instance);

        // 2. Step was retried so active fencing token is 2
        SagaStep step = new SagaStep(UUID.randomUUID(), sagaId, "credit-step", 1, "EXECUTING");
        step.setFencingToken(2L);
        sagaStepRepository.saveAndFlush(step);

        // 3. Stale response presenting token 1 arrives
        assertThatThrownBy(() -> sagaCoordinator.validateStepFencing(sagaId, 1, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZOMBIE_STEP_REJECTED")
                .hasMessageContaining("Stale fencing token 1 < active 2");

        // 4. Fresh response presenting active token 2 succeeds
        sagaCoordinator.validateStepFencing(sagaId, 1, 2L);
    }
}

