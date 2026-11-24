package io.deltaflow.security;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.security.dto.AuthLoginRequest;
import io.deltaflow.security.dto.AuthLoginResponse;
import io.deltaflow.security.dto.CurrentUserProfileDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Stateless Dual-Mode Authentication Integration Test")
@TestPropertySource(properties = {
        "deltaflow.security.enforce-auth=true",
        "deltaflow.security.rate-limit.enabled=false"
})
class AuthenticationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Should reject unauthenticated requests to protected endpoints with HTTP 401 Unauthorized")
    void shouldRejectUnauthenticatedRequests() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/auth/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("Bearer error=\"unauthorized\"");
        assertThat(response.getBody()).contains("Unauthorized", "Authentication credentials missing or invalid");
    }

    @Test
    @DisplayName("Should authenticate operator via JWT bearer token and establish identity context")
    void shouldAuthenticateOperatorViaJwt() {
        // 1. Log in as operator
        AuthLoginRequest loginRequest = new AuthLoginRequest("admin", "password123", "tenant-alpha");
        ResponseEntity<AuthLoginResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login",
                loginRequest,
                AuthLoginResponse.class
        );

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        String token = loginResponse.getBody().token();
        assertThat(token).isNotBlank();

        // 2. Query /api/v1/auth/me using Bearer token
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<CurrentUserProfileDto> meResponse = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                entity,
                CurrentUserProfileDto.class
        );

        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        CurrentUserProfileDto profile = meResponse.getBody();
        assertThat(profile).isNotNull();
        assertThat(profile.principalId()).isEqualTo("admin");
        assertThat(profile.tenantId()).isEqualTo("tenant-alpha");
        assertThat(profile.roles()).contains("ROLE_ADMIN");
        assertThat(profile.authType()).isEqualTo("JWT_BEARER");
    }

    @Test
    @DisplayName("Should authenticate machine client via constant-time pre-shared API Key")
    void shouldAuthenticateViaApiKey() {
        // Use pre-seeded test key from V7 migration: df_live_tenant_alpha_secret_key_12345
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "df_live_tenant_alpha_secret_key_12345");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<CurrentUserProfileDto> meResponse = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                entity,
                CurrentUserProfileDto.class
        );

        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        CurrentUserProfileDto profile = meResponse.getBody();
        assertThat(profile).isNotNull();
        assertThat(profile.principalId()).isEqualTo("key-alpha-service");
        assertThat(profile.tenantId()).isEqualTo("tenant-alpha");
        assertThat(profile.roles()).contains("ROLE_SERVICE");
        assertThat(profile.authType()).isEqualTo("API_KEY");
    }

    @Test
    @DisplayName("Should reject invalid or tampered bearer tokens with HTTP 401")
    void shouldRejectTamperedToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("invalid.fake.jwt.token.signature");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
