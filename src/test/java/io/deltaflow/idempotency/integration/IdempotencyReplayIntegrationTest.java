package io.deltaflow.idempotency.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

public class IdempotencyReplayIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("US1: Initial payment request succeeds and replay returns cached response without duplicate execution")
    void testInitialExecutionAndCachedReplay() {
        String idempotencyKey = "test_replay_" + System.currentTimeMillis();
        String payload = """
            {
                "sourceAccountId": "acc_1001",
                "targetAccountId": "acc_2002",
                "amount": 500.00,
                "currency": "USD"
            }
            """;

        // Step 1: Initial Request
        String initialTxId = given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", idempotencyKey)
            .body(payload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(201)
            .header("Idempotency-Status", "DIRECT")
            .body("status", equalTo("COMPLETED"))
            .body("amount", equalTo(500.0f))
            .body("currency", equalTo("USD"))
            .body("transactionId", notNullValue())
            .extract()
            .path("transactionId");

        // Step 2: Replay Identical Request
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", idempotencyKey)
            .body(payload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(201)
            .header("Idempotency-Status", "CACHED")
            .body("transactionId", equalTo(initialTxId))
            .body("status", equalTo("COMPLETED"))
            .body("amount", equalTo(500.0f))
            .body("currency", equalTo("USD"));
    }
}

