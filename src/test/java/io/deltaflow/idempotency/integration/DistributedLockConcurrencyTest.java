package io.deltaflow.idempotency.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.demo.api.PaymentController;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class DistributedLockConcurrencyTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PaymentController paymentController;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("US3: 20 concurrent requests with same Idempotency-Key execute downstream exactly once")
    void testConcurrentLockContention() throws InterruptedException, ExecutionException {
        String idempotencyKey = "test_concurrent_" + System.currentTimeMillis();
        String payload = """
            {
                "sourceAccountId": "acc_1001",
                "targetAccountId": "acc_2002",
                "amount": 50.00,
                "currency": "USD"
            }
            """;

        int initialExecCount = paymentController.getExecutionCount();
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startGate.await(); // synchronous release
                return given()
                    .contentType(ContentType.JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(payload)
                    .when()
                    .post("/api/v1/payments")
                    .then()
                    .extract()
                    .statusCode();
            }));
        }

        // Trigger all concurrent requests simultaneously
        startGate.countDown();

        int successCount = 0;
        int conflictCount = 0;

        for (Future<Integer> future : futures) {
            int code = future.get();
            if (code == 201) {
                successCount++;
            } else if (code == 409) {
                conflictCount++;
            }
        }
        executor.shutdown();

        // INVARIANCE: Downstream execution count must only increase by 1
        int finalExecCount = paymentController.getExecutionCount();
        assertThat(finalExecCount - initialExecCount).isEqualTo(1);

        // Every request must either receive 201 (cached or direct) or 409 (in-flight conflict)
        assertThat(successCount + conflictCount).isEqualTo(threadCount);
        assertThat(successCount).isGreaterThanOrEqualTo(1);
    }
}

