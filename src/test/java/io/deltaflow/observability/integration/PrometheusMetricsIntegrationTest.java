package io.deltaflow.observability.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.observability.metrics.DeltaFlowMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Prometheus Metrics Actuator Integration Test")
class PrometheusMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeltaFlowMetrics deltaFlowMetrics;

    @Test
    @DisplayName("Should expose /actuator/prometheus and contain custom DeltaFlow domain metrics")
    void shouldExposePrometheusEndpointWithCustomMetrics() {
        // Record test metrics
        deltaFlowMetrics.recordIdempotencyRequest("direct");
        deltaFlowMetrics.recordIdempotencyRequest("cached");
        deltaFlowMetrics.recordSagaDuration("account-transfer", "COMPLETED", Duration.ofMillis(45));
        deltaFlowMetrics.recordLockAcquisition("redis", Duration.ofMillis(5));
        deltaFlowMetrics.recordStepTransition("debit-step", "COMPLETED");
        deltaFlowMetrics.recordCompensation("account-transfer");

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        String body = response.getBody();
        assertThat(body).contains("deltaflow_idempotency_requests_total");
        assertThat(body).contains("status=\"direct\"");
        assertThat(body).contains("status=\"cached\"");
        assertThat(body).contains("deltaflow_saga_duration_seconds");
        assertThat(body).contains("deltaflow_lock_acquisition_time_seconds");
        assertThat(body).contains("deltaflow_saga_steps_active");
        assertThat(body).contains("deltaflow_saga_steps_total");
        assertThat(body).contains("deltaflow_compensations_total");
    }
}
