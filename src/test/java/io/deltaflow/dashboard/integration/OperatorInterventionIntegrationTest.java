package io.deltaflow.dashboard.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.dashboard.dto.OperatorInterventionRequest;
import io.deltaflow.dashboard.dto.OperatorInterventionResult;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.deltaflow.saga.service.SagaJournalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class OperatorInterventionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SagaJournalStore journalStore;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @Test
    @DisplayName("US3: Operator intervention force-compensates halted saga, updates fencing tokens, and logs audit")
    void testForceCompensateHaltedSaga() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "test-tenant-intervene";
        String idempotencyKey = "key-intervene-" + UUID.randomUUID();

        // 1. Create a failed/halted saga instance
        journalStore.createSagaInstance(sagaId, tenantId, idempotencyKey, "account-transfer", "{\"amount\":500}");
        SagaStep step0 = journalStore.recordStepIntent(sagaId, 0, "debit-source", "{\"account\":\"A\"}");
        journalStore.recordStepCompletion(sagaId, 0, "{\"status\":\"DEBITED\"}");

        SagaStep step1 = journalStore.recordStepIntent(sagaId, 1, "credit-target", "{\"account\":\"B\"}");
        journalStore.recordStepFailure(sagaId, 1, "Connection timed out to core banking API");
        journalStore.failSaga(sagaId, "Step 1 failed");

        long initialFencingToken = step1.getFencingToken();

        // 2. Submit manual intervention: FORCE_COMPENSATE
        OperatorInterventionRequest request = new OperatorInterventionRequest(
                "FORCE_COMPENSATE",
                null,
                "operator_mayukh_99",
                "Customer requested manual settlement and rollback after gateway timeout"
        );

        ResponseEntity<OperatorInterventionResult> response = restTemplate.postForEntity(
                "/api/v1/dashboard/sagas/" + sagaId + "/intervene",
                request,
                OperatorInterventionResult.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        OperatorInterventionResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.sagaId()).isEqualTo(sagaId);
        assertThat(result.actionTaken()).isEqualTo("FORCE_COMPENSATE");
        assertThat(result.previousStatus()).isEqualTo("FAILED");
        assertThat(result.newStatus()).isEqualTo("COMPENSATED");
        assertThat(result.operatorId()).isEqualTo("operator_mayukh_99");
        assertThat(result.auditEventId()).isNotBlank();

        // 3. Verify database state
        SagaInstance updatedInstance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(updatedInstance.getStatus()).isEqualTo("COMPENSATED");

        SagaStep updatedStep1 = sagaStepRepository.findBySagaIdAndStepIndex(sagaId, 1).orElseThrow();
        assertThat(updatedStep1.getFencingToken()).isGreaterThan(initialFencingToken);
        assertThat(updatedStep1.getStatus()).isEqualTo("COMPENSATED");
    }

    @Test
    @DisplayName("US3: Operator intervention rejects active executing saga with 409 Conflict (Zero Double-Spend)")
    void testActiveLeaseConflict() {
        UUID sagaId = UUID.randomUUID();
        String tenantId = "test-tenant-lease";
        String idempotencyKey = "key-lease-" + UUID.randomUUID();

        // Create an actively executing saga
        journalStore.createSagaInstance(sagaId, tenantId, idempotencyKey, "account-transfer", "{\"amount\":250}");
        journalStore.recordStepIntent(sagaId, 0, "debit-source", "{\"account\":\"A\"}");

        // Attempt intervention while actively executing
        OperatorInterventionRequest request = new OperatorInterventionRequest(
                "FORCE_COMPENSATE",
                null,
                "operator_admin",
                "Force rollback attempt on active saga"
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/dashboard/sagas/" + sagaId + "/intervene",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("US3: Operator intervention rejects short justification (< 10 chars) with 400 Bad Request")
    void testShortJustificationValidation() {
        UUID sagaId = UUID.randomUUID();
        OperatorInterventionRequest request = new OperatorInterventionRequest(
                "FORCE_COMPENSATE",
                null,
                "operator_admin",
                "too short"
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/dashboard/sagas/" + sagaId + "/intervene",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
