package io.deltaflow.chaos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.chaos.domain.InvariantVerificationReport;
import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.domain.IdempotencyRecordId;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class FinancialInvariantVerifierTest {

    private SagaInstanceRepository sagaInstanceRepository;
    private SagaStepRepository sagaStepRepository;
    private IdempotencyRecordRepository idempotencyRecordRepository;
    private FinancialInvariantVerifier verifier;

    @BeforeEach
    void setUp() {
        sagaInstanceRepository = Mockito.mock(SagaInstanceRepository.class);
        sagaStepRepository = Mockito.mock(SagaStepRepository.class);
        idempotencyRecordRepository = Mockito.mock(IdempotencyRecordRepository.class);
        verifier = new FinancialInvariantVerifier(
                sagaInstanceRepository,
                sagaStepRepository,
                idempotencyRecordRepository,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("Should prove mathematical invariant Total Debits == Total Credits + Total Refunds with 0.00 discrepancy")
    void shouldProveBalanceConservation() {
        UUID saga1 = UUID.randomUUID();
        UUID saga2 = UUID.randomUUID();

        // 1 completed saga ($100), 1 compensated saga ($50)
        SagaInstance completedSaga = new SagaInstance(saga1, "tenant-1", "key-1", "transfer", "{\"amount\":100.00}");
        completedSaga.setStatus("COMPLETED");

        SagaInstance compensatedSaga = new SagaInstance(saga2, "tenant-1", "key-2", "transfer", "{\"amount\":50.00}");
        compensatedSaga.setStatus("COMPENSATED");

        when(sagaInstanceRepository.findAll()).thenReturn(List.of(completedSaga, compensatedSaga));

        IdempotencyRecord rec1 = new IdempotencyRecord(new IdempotencyRecordId("tenant-1", "key-1"), "req1", "RESOLVED", java.time.Instant.now().plusSeconds(60));
        IdempotencyRecord rec2 = new IdempotencyRecord(new IdempotencyRecordId("tenant-1", "key-2"), "req2", "RESOLVED", java.time.Instant.now().plusSeconds(60));
        when(idempotencyRecordRepository.findAll()).thenReturn(List.of(rec1, rec2));

        InvariantVerificationReport report = verifier.verifyInvariants();

        assertThat(report.status()).isEqualTo("PASSED");
        assertThat(report.totalTransactionsAudited()).isEqualTo(2);
        assertThat(report.successfulTransactions()).isEqualTo(1);
        assertThat(report.compensatedTransactions()).isEqualTo(1);
        assertThat(report.failedTransactions()).isEqualTo(0);
        assertThat(report.totalSourceDebits()).isEqualByComparingTo(BigDecimal.valueOf(150.00));
        assertThat(report.totalTargetCredits()).isEqualByComparingTo(BigDecimal.valueOf(100.00));
        assertThat(report.totalRefundedCredits()).isEqualByComparingTo(BigDecimal.valueOf(50.00));
        assertThat(report.discrepancyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.doubleSpendCount()).isEqualTo(0);
        assertThat(report.orphanedDebitCount()).isEqualTo(0);
        assertThat(report.violations()).isEmpty();
    }

    @Test
    @DisplayName("Should flag orphaned debit when step 0 completed without compensation on saga failure")
    void shouldFlagOrphanedDebitOnFailedSaga() {
        UUID sagaId = UUID.randomUUID();
        SagaInstance failedSaga = new SagaInstance(sagaId, "tenant-1", "key-f", "transfer", "{\"amount\":200.00}");
        failedSaga.setStatus("FAILED");

        SagaStep step0 = new SagaStep(UUID.randomUUID(), sagaId, "debit-source", 0, "COMPLETED");
        step0.setStatus("COMPLETED");

        when(sagaInstanceRepository.findAll()).thenReturn(List.of(failedSaga));
        when(sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId)).thenReturn(List.of(step0));
        when(idempotencyRecordRepository.findAll()).thenReturn(List.of());

        InvariantVerificationReport report = verifier.verifyInvariants();

        assertThat(report.status()).isEqualTo("VIOLATED");
        assertThat(report.orphanedDebitCount()).isEqualTo(1);
        assertThat(report.violations()).anyMatch(v -> "ORPHANED_DEBIT".equals(v.violationType()));
    }
}
