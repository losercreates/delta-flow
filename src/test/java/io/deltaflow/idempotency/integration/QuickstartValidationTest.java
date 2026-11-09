package io.deltaflow.idempotency.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end integration test validating the exact scenarios documented in quickstart.md.
 */
public class QuickstartValidationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("Quickstart Validation: Scenario 1 (Direct), Scenario 2 (Cached Replay), Scenario 3 (Tampering)")
    void testQuickstartScenarios() {
        String key = "pay_demo_quickstart_" + System.currentTimeMillis();
        String payload = """
            {
                "sourceAccountId": "acc_1001",
                "targetAccountId": "acc_2002",
                "amount": 500.00,
                "currency": "USD"
            }
            """;

        // Scenario 1: Initial Payment Request (Cache Miss & Execution)
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .body(payload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(201)
            .header("Idempotency-Status", "DIRECT")
            .body("status", equalTo("COMPLETED"))
            .body("transactionId", notNullValue());

        // Scenario 2: Identical Replay (Cache Hit & Zero Downstream Re-execution)
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .body(payload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(201)
            .header("Idempotency-Status", "CACHED")
            .body("status", equalTo("COMPLETED"));

        // Scenario 3: Payload Tampering Detection (Hash Mismatch -> 409 Conflict)
        String tamperedPayload = """
            {
                "sourceAccountId": "acc_1001",
                "targetAccountId": "acc_2002",
                "amount": 999.00,
                "currency": "USD"
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", key)
            .body(tamperedPayload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(409)
            .body("errorCode", equalTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"))
            .body("idempotencyKey", equalTo(key));

        // Inspector check: verify operator endpoint
        given()
            .when()
            .get("/api/v1/idempotency/records/" + key)
            .then()
            .statusCode(200)
            .body("status", equalTo("RESOLVED"))
            .body("responseStatusCode", equalTo(201));
    }
}

