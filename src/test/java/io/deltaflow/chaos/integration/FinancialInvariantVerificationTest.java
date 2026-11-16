package io.deltaflow.chaos.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.chaos.domain.InvariantVerificationReport;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.service.SagaJournalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class FinancialInvariantVerificationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaJournalStore journalStore;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Test
    @DisplayName("US2: Invariant verifier confirms 0.00 discrepancy across successful and compensated sagas")
    void testFinancialInvariantVerificationPassed() {
        // 1. Create a completed transfer saga: $200.00 debited, $200.00 credited
        UUID saga1 = UUID.randomUUID();
        journalStore.createSagaInstance(saga1, "tenant-inv", "key-inv-1", "account-transfer", "{\"amount\":200.00}");
        journalStore.recordStepIntent(saga1, 0, "debit-source", "{\"account\":\"acc_1\"}");
        journalStore.recordStepCompletion(saga1, 0, "{\"status\":\"DEBITED\"}");
        journalStore.recordStepIntent(saga1, 1, "credit-target", "{\"account\":\"acc_2\"}");
        journalStore.recordStepCompletion(saga1, 1, "{\"status\":\"CREDITED\"}");
        journalStore.completeSaga(saga1, "{\"final\":\"COMPLETED\"}");

        // 2. Create a compensated transfer saga: $150.00 debited, then refunded
        UUID saga2 = UUID.randomUUID();
        journalStore.createSagaInstance(saga2, "tenant-inv", "key-inv-2", "account-transfer", "{\"amount\":150.00}");
        journalStore.recordStepIntent(saga2, 0, "debit-source", "{\"account\":\"acc_3\"}");
        journalStore.recordStepCompletion(saga2, 0, "{\"status\":\"DEBITED\"}");
        journalStore.recordStepIntent(saga2, 1, "credit-target", "{\"account\":\"acc_4\"}");
        journalStore.recordStepFailure(saga2, 1, "Credit failed");
        journalStore.startSagaCompensation(saga2, 1, "Credit step failed");
        journalStore.recordStepCompensating(saga2, 0);
        journalStore.recordStepCompensated(saga2, 0, "{\"refund\":\"COMPENSATED\"}");
        journalStore.completeSagaCompensation(saga2, "{\"status\":\"COMPENSATED\"}");

        // 3. Query Invariant Verification API
        ResponseEntity<InvariantVerificationReport> response = restTemplate.getForEntity(
                "/api/v1/invariants/verify",
                InvariantVerificationReport.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        InvariantVerificationReport report = response.getBody();
        assertThat(report).isNotNull();

        assertThat(report.status()).isEqualTo("PASSED");
        assertThat(report.discrepancyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.doubleSpendCount()).isEqualTo(0);
        assertThat(report.orphanedDebitCount()).isEqualTo(0);
        assertThat(report.violations()).isEmpty();
    }
}
