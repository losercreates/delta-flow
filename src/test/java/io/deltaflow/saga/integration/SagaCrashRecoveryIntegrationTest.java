package io.deltaflow.saga.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.api.dto.TransferRequestDto;
import io.deltaflow.saga.demo.TransferSagaConfig;
import io.deltaflow.saga.demo.service.DebitService;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.deltaflow.saga.service.SagaRecoveryScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SagaCrashRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SagaRecoveryScanner sagaRecoveryScanner;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Autowired
    private DebitService debitService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        debitService.reset();
    }

    @Test
    @DisplayName("Crash recovery scanner claims expired lease and executes backward compensation")
    void testCrashRecoveryClaimsExpiredLeaseAndCompensates() throws Exception {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "tenant-recovery-test";
        String idempotencyKey = "recovery-idemp-" + UUID.randomUUID();

        TransferRequestDto request = new TransferRequestDto(
                "acc_source_recovery",
                "acc_target_recovery",
                200.00,
                "USD"
        );
        String payloadJson = objectMapper.writeValueAsString(request);

        // 1. Create orphaned saga instance with EXECUTING status
        SagaInstance instance = new SagaInstance(
                sagaId,
                tenantId,
                idempotencyKey,
                TransferSagaConfig.SAGA_NAME,
                payloadJson
        );
        instance.setStatus("EXECUTING");
        instance.setCurrentStep(1);
        sagaInstanceRepository.saveAndFlush(instance);

        // 2. Insert completed Step 0 (debit-step)
        SagaStep step0 = new SagaStep(UUID.randomUUID(), sagaId, "debit-step", 0, "COMPLETED");
        step0.setRequestPayload(payloadJson);
        step0.setResponsePayload("{\"debitId\":\"deb_rec_001\",\"amount\":200.00,\"currency\":\"USD\"}");
        step0.setFencingToken(1L);
        sagaStepRepository.saveAndFlush(step0);

        // 3. Insert in-flight Step 1 (credit-step) that was interrupted by process crash
        SagaStep step1 = new SagaStep(UUID.randomUUID(), sagaId, "credit-step", 1, "EXECUTING");
        step1.setRequestPayload(payloadJson);
        step1.setFencingToken(1L);
        sagaStepRepository.saveAndFlush(step1);

        // 4. Force updated_at back to 30 seconds ago to simulate expired lease
        Instant expiredTime = Instant.now().minus(30, ChronoUnit.SECONDS);
        jdbcTemplate.update(
                "UPDATE saga_instances SET updated_at = ? WHERE saga_id = ?",
                Timestamp.from(expiredTime),
                sagaId
        );

        // Verify initial state
        assertThat(debitService.getCompensationCount()).isEqualTo(0);

        // 5. Trigger the crash recovery scanner
        int recovered = sagaRecoveryScanner.runRecoveryScan();
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        // 6. Verify that DebitService compensation was executed
        assertThat(debitService.getCompensationCount()).isGreaterThanOrEqualTo(1);

        // 7. Verify saga instance transitioned to COMPENSATED
        SagaInstance recoveredInstance = sagaInstanceRepository.findById(sagaId)
                .orElseThrow(() -> new AssertionError("Saga instance missing"));
        assertThat(recoveredInstance.getStatus()).isEqualTo("COMPENSATED");
        assertThat(recoveredInstance.getResultPayload()).contains("COMPENSATED");

        // 8. Verify step 0 status transitioned to COMPENSATED
        List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getStatus()).isEqualTo("COMPENSATED");
    }
}
