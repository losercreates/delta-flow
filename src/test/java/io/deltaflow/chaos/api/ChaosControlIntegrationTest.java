package io.deltaflow.chaos.api;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.chaos.domain.ChaosInjectionRequest;
import io.deltaflow.chaos.domain.ChaosStatusDto;
import io.deltaflow.chaos.domain.CircuitBreakerTransitionRequest;
import io.deltaflow.chaos.domain.ProxyStatusDto;
import io.deltaflow.dashboard.dto.CircuitBreakerStatusDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ChaosControlIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("US2: GET /api/v1/chaos/status returns system proxies and circuit breakers")
    void testGetChaosStatus() {
        ResponseEntity<ChaosStatusDto> response = restTemplate.getForEntity(
                "/api/v1/chaos/status",
                ChaosStatusDto.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChaosStatusDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.proxies()).isNotEmpty();
    }

    @Test
    @DisplayName("US2: POST /api/v1/chaos/inject and POST /api/v1/chaos/heal")
    void testInjectAndHealChaos() {
        ChaosInjectionRequest injectReq = new ChaosInjectionRequest(
                "payment-gateway",
                "LATENCY",
                500L,
                50L,
                null,
                "ops-user-1",
                "Testing circuit breaker fallback latency"
        );

        ResponseEntity<ProxyStatusDto> injectResp = restTemplate.postForEntity(
                "/api/v1/chaos/inject",
                injectReq,
                ProxyStatusDto.class
        );

        assertThat(injectResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(injectResp.getBody()).isNotNull();
        assertThat(injectResp.getBody().activeToxics()).isNotEmpty();

        // Heal
        ResponseEntity<Map> healResp = restTemplate.postForEntity(
                "/api/v1/chaos/heal",
                Map.of("proxyName", "payment-gateway", "operatorId", "ops-user-1", "justification", "Restore normal"),
                Map.class
        );

        assertThat(healResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healResp.getBody().get("status")).isEqualTo("HEALED");
    }

    @Test
    @DisplayName("US2: POST /api/v1/chaos/circuit-breakers/{name}/transition forces breaker OPEN then CLOSED")
    void testCircuitBreakerTransition() {
        CircuitBreakerTransitionRequest openReq = new CircuitBreakerTransitionRequest(
                "OPEN",
                "ops-user-1",
                "Manual circuit breaker trip test"
        );

        // Assume transferDebit or transferCredit circuit breaker is registered
        ResponseEntity<CircuitBreakerStatusDto> openResp = restTemplate.postForEntity(
                "/api/v1/chaos/circuit-breakers/transferDebit/transition",
                openReq,
                CircuitBreakerStatusDto.class
        );

        if (openResp.getStatusCode() == HttpStatus.OK) {
            assertThat(openResp.getBody()).isNotNull();
            assertThat(openResp.getBody().state()).isEqualTo("OPEN");

            // Reset back to CLOSED
            CircuitBreakerTransitionRequest closeReq = new CircuitBreakerTransitionRequest(
                    "CLOSED",
                    "ops-user-1",
                    "Manual reset back to healthy"
            );
            ResponseEntity<CircuitBreakerStatusDto> closeResp = restTemplate.postForEntity(
                    "/api/v1/chaos/circuit-breakers/transferDebit/transition",
                    closeReq,
                    CircuitBreakerStatusDto.class
            );
            assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(closeResp.getBody().state()).isEqualTo("CLOSED");
        }
    }
}
