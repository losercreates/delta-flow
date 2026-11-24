package io.deltaflow.security;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.security.dto.AuthLoginRequest;
import io.deltaflow.security.dto.AuthLoginResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Role-Based Access Control (RBAC) & Privilege Separation Integration Test")
@TestPropertySource(properties = {
        "deltaflow.security.enforce-auth=true",
        "deltaflow.security.rate-limit.enabled=false"
})
class RbacAuthorizationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private String getOperatorToken(String role) {
        String username = role.toLowerCase().replace("role_", "");
        AuthLoginRequest request = new AuthLoginRequest(username, "password123", "tenant-rbac");
        ResponseEntity<AuthLoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                request,
                AuthLoginResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().token();
    }

    @Test
    @DisplayName("ROLE_VIEWER should be denied access to chaos injection with HTTP 403 Forbidden")
    void viewerShouldBeDeniedChaosInjection() {
        String viewerToken = getOperatorToken("viewer");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(viewerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> chaosPayload = Map.of(
                "proxyName", "payment-gateway",
                "toxicType", "LATENCY",
                "operatorId", "viewer",
                "justification", "Viewer unauthorized chaos test"
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(chaosPayload, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/chaos/inject",
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("Forbidden", "Access Denied");
    }

    @Test
    @DisplayName("ROLE_OPERATOR should be denied access to chaos injection but permitted for cluster inspection")
    void operatorShouldBeDeniedChaosButPermittedInspection() {
        String operatorToken = getOperatorToken("operator");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(operatorToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. Attempt chaos injection -> 403 Forbidden
        HttpEntity<Map<String, Object>> chaosEntity = new HttpEntity<>(
                Map.of("proxyName", "payment-gateway", "toxicType", "LATENCY", "operatorId", "operator", "justification", "Op test"),
                headers
        );
        ResponseEntity<String> chaosRes = restTemplate.postForEntity("/api/v1/chaos/inject", chaosEntity, String.class);
        assertThat(chaosRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 2. Query cluster nodes (read-only) -> 200 OK
        HttpEntity<Void> readEntity = new HttpEntity<>(headers);
        ResponseEntity<String> clusterRes = restTemplate.exchange("/api/v1/cluster/nodes", HttpMethod.GET, readEntity, String.class);
        assertThat(clusterRes.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("ROLE_ADMIN should be authorized to access chaos control and retention sweep endpoints")
    void adminShouldBeAuthorizedForAdminEndpoints() {
        String adminToken = getOperatorToken("admin");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Trigger on-demand lease reclamation (admin endpoint)
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> reclaimRes = restTemplate.postForEntity(
                "/api/v1/cluster/outbox/reclaim-leases",
                entity,
                String.class
        );
        assertThat(reclaimRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reclaimRes.getBody()).contains("scannedCount", "reclaimedCount");
    }

    @Test
    @DisplayName("ROLE_SERVICE (API Key) should be denied access to operator dashboard endpoints")
    void serviceKeyShouldBeDeniedDashboardAccess() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "df_live_tenant_alpha_secret_key_12345");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/cluster/nodes",
                HttpMethod.GET,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
