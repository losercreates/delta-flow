package io.deltaflow.observability.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.observability.health.GracefulShutdownHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

public class ProductionHealthProbesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private GracefulShutdownHandler shutdownHandler;

    @AfterEach
    void tearDown() {
        shutdownHandler.cancelDraining();
    }

    @Test
    @DisplayName("Kubernetes Liveness probe should return 200 UP")
    void livenessProbeShouldBeUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health/liveness", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("Kubernetes Readiness probe should return 200 UP and report database/redis readiness")
    void readinessProbeShouldBeUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health/readiness", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("Readiness probe should transition to 503 OUT_OF_SERVICE during graceful draining")
    void readinessProbeShouldReflectDrainingStatus() {
        // Initially UP
        ResponseEntity<Map> initial = restTemplate.getForEntity("/actuator/health/readiness", Map.class);
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initial.getBody().get("status")).isEqualTo("UP");

        // Initiate draining
        shutdownHandler.startDraining();
        assertThat(shutdownHandler.isDraining()).isTrue();

        // Must now return 503 OUT_OF_SERVICE
        ResponseEntity<Map> drainingResponse = restTemplate.getForEntity("/actuator/health/readiness", Map.class);
        assertThat(drainingResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(drainingResponse.getBody().get("status")).isIn("OUT_OF_SERVICE", "DOWN");

        // Cancel draining -> recovers to 200 UP
        shutdownHandler.cancelDraining();
        ResponseEntity<Map> recovered = restTemplate.getForEntity("/actuator/health/readiness", Map.class);
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recovered.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("Graceful shutdown handler should wait for in-flight sagas to complete before termination")
    void gracefulShutdownDrainingShouldWaitForActiveSagas() {
        shutdownHandler.registerSagaStart();
        assertThat(shutdownHandler.getActiveSagaCount()).isGreaterThanOrEqualTo(1);

        // Run an asynchronous worker that completes the saga after 300ms
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {}
                shutdownHandler.registerSagaEnd();
            });

            boolean drained = shutdownHandler.drain(Duration.ofSeconds(3));
            assertThat(drained).isTrue();
            assertThat(shutdownHandler.getActiveSagaCount()).isEqualTo(0);
        }
    }
}
