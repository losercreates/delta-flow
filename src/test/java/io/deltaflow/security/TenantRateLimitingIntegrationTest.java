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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Multi-Tenant Distributed Token-Bucket Rate Limiting Integration Test")
@TestPropertySource(properties = {
        "deltaflow.security.enforce-auth=true",
        "deltaflow.security.rate-limit.enabled=true",
        "deltaflow.security.rate-limit.default-burst=5"
})
class TenantRateLimitingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private String getTenantToken(String tenantId) {
        AuthLoginRequest request = new AuthLoginRequest("admin", "password123", tenantId);
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
    @DisplayName("Should throttle requests with HTTP 429 when tenant exceeds token bucket capacity")
    void shouldThrottleTenantExceedingBurstCapacity() {
        String tenantAlpha = "tenant-limit-alpha";
        String tokenAlpha = getTenantToken(tenantAlpha);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenAlpha);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        int successfulRequests = 0;
        int rateLimitedRequests = 0;
        ResponseEntity<String> lastResponse = null;

        // Burst 5 capacity configured above; send 12 rapid requests
        for (int i = 0; i < 12; i++) {
            lastResponse = restTemplate.exchange(
                    "/api/v1/auth/me",
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (lastResponse.getStatusCode() == HttpStatus.OK) {
                successfulRequests++;
                assertThat(lastResponse.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("5");
                assertThat(lastResponse.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
            } else if (lastResponse.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                rateLimitedRequests++;
                assertThat(lastResponse.getHeaders().getFirst("Retry-After")).isEqualTo("1");
                assertThat(lastResponse.getBody()).contains("Too Many Requests", "Rate limit exceeded for tenant");
            }
        }

        assertThat(successfulRequests).isGreaterThanOrEqualTo(1);
        assertThat(rateLimitedRequests).isGreaterThan(0);
    }

    @Test
    @DisplayName("Throttling on Tenant A should have zero impact on Tenant B (strict multi-tenant isolation)")
    void throttlingTenantAShouldNotAffectTenantB() {
        String tenantA = "tenant-isolated-a";
        String tenantB = "tenant-isolated-b";

        String tokenA = getTenantToken(tenantA);
        String tokenB = getTenantToken(tenantB);

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);

        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(tokenB);

        // 1. Exhaust Tenant A's token bucket
        for (int i = 0; i < 10; i++) {
            restTemplate.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(headersA), String.class);
        }

        // Verify Tenant A is currently throttled
        ResponseEntity<String> responseA = restTemplate.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(headersA), String.class);
        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // 2. Concurrently query with Tenant B -> MUST SUCCEED with HTTP 200 OK
        ResponseEntity<String> responseB = restTemplate.exchange("/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(headersB), String.class);
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseB.getBody()).contains(tenantB);
    }
}
