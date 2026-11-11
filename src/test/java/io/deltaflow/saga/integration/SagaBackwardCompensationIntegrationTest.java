package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.demo.service.DebitService;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.SagaStep;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.SagaStepRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

public class SagaBackwardCompensationIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private DebitService debitService;

    @Autowired
    private SagaInstanceRepository sagaInstanceRepository;

    @Autowired
    private SagaStepRepository sagaStepRepository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("US1: Injected credit step failure triggers reverse backward compensation of debit step")
    void testAutomaticBackwardCompensationOnStepFailure() {
        String idempotencyKey = "transfer-fail-" + UUID.randomUUID();
        String payload = """
            {
                "sourceAccountId": "acc_source_100",
                "targetAccountId": "acc_frozen_error",
                "amount": 250.00,
                "currency": "USD"
            }
            """;

        int initialDebitCount = debitService.getExecutionCount();
        int initialCompensationCount = debitService.getCompensationCount();

        // 1. Initial execution that fails at step 1 (credit-step)
        String sagaIdStr = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Tenant-ID", "tenant-compensation-test")
            .body(payload)
            .when()
            .post("/api/v1/transfers")
            .then()
            .statusCode(422)
            .header("Idempotency-Status", "DIRECT")
            .body("status", equalTo("COMPENSATED"))
            .body("failedStep", equalTo("credit-step"))
            .body("errorMessage", containsString("Target account frozen or credit rejected"))
            .body("compensatedSteps", hasItem("debit-step"))
            .extract()
            .path("sagaId");

        assertThat(sagaIdStr).isNotNull();
        UUID sagaId = UUID.fromString(sagaIdStr);

        // Verify debit was executed then compensated (refunded)
        assertThat(debitService.getExecutionCount() - initialDebitCount).isEqualTo(1);
        assertThat(debitService.getCompensationCount() - initialCompensationCount).isEqualTo(1);
        assertThat(debitService.isCompensated(idempotencyKey)).isTrue();

        // Verify relational database journal state
        SagaInstance instance = sagaInstanceRepository.findById(sagaId).orElseThrow();
        assertThat(instance.getStatus()).isEqualTo("COMPENSATED");
        assertThat(instance.getCurrentStep()).isEqualTo(1);

        List<SagaStep> steps = sagaStepRepository.findBySagaIdOrderByStepIndexAsc(sagaId);
        assertThat(steps).hasSizeGreaterThanOrEqualTo(2);

        SagaStep step0 = steps.get(0);
        assertThat(step0.getStepName()).isEqualTo("debit-step");
        assertThat(step0.getStatus()).isEqualTo("COMPENSATED");

        SagaStep step1 = steps.get(1);
        assertThat(step1.getStepName()).isEqualTo("credit-step");
        assertThat(step1.getStatus()).isEqualTo("FAILED");
        assertThat(step1.getErrorDetails()).contains("Target account frozen or credit rejected");

        // 2. Replay with the identical Idempotency-Key -> cached 422 response with zero duplicate execution
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Tenant-ID", "tenant-compensation-test")
            .body(payload)
            .when()
            .post("/api/v1/transfers")
            .then()
            .statusCode(422)
            .header("Idempotency-Status", "CACHED")
            .body("status", equalTo("COMPENSATED"))
            .body("sagaId", equalTo(sagaIdStr))
            .body("failedStep", equalTo("credit-step"));

        // Invariant: no duplicate execution or compensation occurred
        assertThat(debitService.getExecutionCount() - initialDebitCount).isEqualTo(1);
        assertThat(debitService.getCompensationCount() - initialCompensationCount).isEqualTo(1);
    }
}

