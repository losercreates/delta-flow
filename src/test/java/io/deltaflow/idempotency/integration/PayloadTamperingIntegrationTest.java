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

public class PayloadTamperingIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("US2: Reusing Idempotency-Key with different payload triggers HTTP 409 Conflict")
    void testPayloadTamperingConflict() {
        String idempotencyKey = "test_tamper_" + System.currentTimeMillis();
        String initialPayload = """
            {
                "sourceAccountId": "acc_1001",
                "targetAccountId": "acc_2002",
                "amount": 100.00,
                "currency": "USD"
            }
            """;

        // Step 1: Initial successful request
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", idempotencyKey)
            .body(initialPayload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(201);

        // Step 2: Attempted tampering with altered amount
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
            .header("Idempotency-Key", idempotencyKey)
            .body(tamperedPayload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(409)
            .body("errorCode", equalTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"))
            .body("idempotencyKey", equalTo(idempotencyKey));
    }
}

