package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.deltaflow.saga.service.SagaJournalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SagaWalPersistenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaJournalStore sagaJournalStore;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("WAL engine durably commits step intent to PostgreSQL before execution")
    void testPreDispatchWalPersistence() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-wal-test";
        String idempotencyKey = "wal-key-" + UUID.randomUUID();
        String sagaType = "ACCOUNT_TRANSFER";
        String payload = "{\"amount\": 250.00, \"currency\": \"USD\"}";

        // 1. Create Saga Instance (Pending)
        SagaInstance instance = sagaJournalStore.createSagaInstance(
                sagaId, tenantId, idempotencyKey, sagaType, payload
        );
        assertThat(instance).isNotNull();
        assertThat(instance.getStatus()).isEqualTo("PENDING");

        // Verify committed directly via raw JDBC
        Integer instanceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saga_instances WHERE saga_id = ?",
                Integer.class,
                sagaId
        );
        assertThat(instanceCount).isEqualTo(1);

        // 2. Record Step 0 Intent (Debit) - WAL pre-dispatch commit
        String debitPayload = "{\"sourceAccount\": \"acc-1\", \"amount\": 250.00}";
        SagaStep debitStep = sagaJournalStore.recordStepIntent(
                sagaId, 0, "debit-step", debitPayload
        );
        assertThat(debitStep.getStatus()).isEqualTo("EXECUTING");

        // Verify step record is already committed in PostgreSQL before downstream dispatch
        Map<String, Object> rawStepRecord = jdbcTemplate.queryForMap(
                "SELECT step_name, status, step_index FROM saga_steps WHERE saga_id = ? AND step_index = ?",
                sagaId, 0
        );
        assertThat(rawStepRecord.get("step_name")).isEqualTo("debit-step");
        assertThat(rawStepRecord.get("status")).isEqualTo("EXECUTING");
        assertThat(rawStepRecord.get("step_index")).isEqualTo(0);

        // Verify saga instance is updated to EXECUTING with current_step = 0
        Map<String, Object> rawInstanceRecord = jdbcTemplate.queryForMap(
                "SELECT status, current_step FROM saga_instances WHERE saga_id = ?",
                sagaId
        );
        assertThat(rawInstanceRecord.get("status")).isEqualTo("EXECUTING");
        assertThat(rawInstanceRecord.get("current_step")).isEqualTo(0);

        // 3. Record Step 0 Completion
        String debitResponse = "{\"txId\": \"tx-debit-123\", \"status\": \"SUCCESS\"}";
        SagaStep completedStep = sagaJournalStore.recordStepCompletion(
                sagaId, 0, debitResponse
        );
        assertThat(completedStep.getStatus()).isEqualTo("COMPLETED");

        Map<String, Object> updatedStepRecord = jdbcTemplate.queryForMap(
                "SELECT status, response_payload FROM saga_steps WHERE saga_id = ? AND step_index = ?",
                sagaId, 0
        );
        assertThat(updatedStepRecord.get("status")).isEqualTo("COMPLETED");
        assertThat(updatedStepRecord.get("response_payload").toString()).contains("tx-debit-123");

        // 4. Complete Saga
        String finalResult = "{\"status\": \"TRANSFER_COMPLETED\", \"amount\": 250.00}";
        SagaInstance completedSaga = sagaJournalStore.completeSaga(sagaId, finalResult);
        assertThat(completedSaga.getStatus()).isEqualTo("COMPLETED");

        String dbSagaStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM saga_instances WHERE saga_id = ?",
                String.class,
                sagaId
        );
        assertThat(dbSagaStatus).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("WAL engine records step failure and updates saga status to FAILED")
    void testStepFailurePersistence() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-wal-test";
        String idempotencyKey = "wal-fail-key-" + UUID.randomUUID();

        sagaJournalStore.createSagaInstance(
                sagaId, tenantId, idempotencyKey, "ACCOUNT_TRANSFER", "{\"amount\": 100.0}"
        );

        sagaJournalStore.recordStepIntent(sagaId, 0, "debit-step", "{\"amount\": 100.0}");
        SagaStep failedStep = sagaJournalStore.recordStepFailure(
                sagaId, 0, "INSUFFICIENT_FUNDS: Available balance is $50.0"
        );

        assertThat(failedStep.getStatus()).isEqualTo("FAILED");
        assertThat(failedStep.getErrorDetails()).contains("INSUFFICIENT_FUNDS");

        SagaInstance failedSaga = sagaJournalStore.failSaga(sagaId, "Step 0 debit-step failed: INSUFFICIENT_FUNDS");
        assertThat(failedSaga.getStatus()).isEqualTo("FAILED");

        List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
    }
}

