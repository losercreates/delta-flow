package io.deltaflow.idempotency.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.domain.IdempotencyRecordId;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import io.deltaflow.idempotency.service.PayloadHasher;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

public class LeaseTimeoutRecoveryIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private PayloadHasher payloadHasher;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("US4: Expired PROCESSING lease allows retry to reclaim lock and complete successfully")
    void testLeaseTimeoutReclamation() {
        String idempotencyKey = "test_lease_expiry_" + System.currentTimeMillis();
        String payload = """
            {
                "sourceAccountId": "acc_1001",
                "targetAccountId": "acc_2002",
                "amount": 250.00,
                "currency": "USD"
            }
            """;
        String requestHash = payloadHasher.computeHash(payload.getBytes(StandardCharsets.UTF_8), null);

        // Pre-insert an orphaned record simulating a crashed instance holding a lock whose lease has expired
        IdempotencyRecord orphanedRecord = new IdempotencyRecord(
            new IdempotencyRecordId("default", idempotencyKey),
            requestHash,
            "PROCESSING",
            Instant.now().minusSeconds(60) // Expired 60s ago
        );
        orphanedRecord.setLockToken("crashed-node-uuid");
        repository.save(orphanedRecord);

        // Now send the retry request - system should detect lease expiration, reclaim the lock, and complete
        given()
            .contentType(ContentType.JSON)
            .header("Idempotency-Key", idempotencyKey)
            .body(payload)
            .when()
            .post("/api/v1/payments")
            .then()
            .statusCode(201)
            .body("status", equalTo("COMPLETED"))
            .body("amount", equalTo(250.0f));

        // Verify the database record is now updated to RESOLVED
        IdempotencyRecord resolved = repository.findById("default", idempotencyKey).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(resolved.getStatus()).isEqualTo("RESOLVED");
    }
}

