package io.deltaflow.saga.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.saga.webhook.WebhookSecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SagaWebhookSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WebhookSecurityService securityService;

    @Test
    @DisplayName("Webhook missing security headers is rejected with HTTP 400 Bad Request")
    void testMissingSecurityHeaders() {
        UUID sagaId = UUID.randomUUID();
        String eventName = "test-event";
        String url = "/api/v1/sagas/" + sagaId + "/events/" + eventName;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{\"status\":\"APPROVED\"}", headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "BAD_REQUEST");
    }

    @Test
    @DisplayName("Webhook with expired timestamp (> 300s) is rejected with HTTP 401 Unauthorized")
    void testExpiredTimestampRejection() {
        UUID sagaId = UUID.randomUUID();
        String eventName = "test-event";
        String url = "/api/v1/sagas/" + sagaId + "/events/" + eventName;

        long expiredTimestamp = Instant.now().minusSeconds(400).toEpochMilli();
        String body = "{\"status\":\"APPROVED\"}";
        String signature = securityService.computeHmacSha256(expiredTimestamp + "." + body, securityService.getDefaultSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", signature);
        headers.set("X-Webhook-Timestamp", String.valueOf(expiredTimestamp));
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("message", "Webhook timestamp expired or outside tolerance window");
    }

    @Test
    @DisplayName("Webhook with forged/tampered signature is rejected with HTTP 401 Unauthorized")
    void testForgedSignatureRejection() {
        UUID sagaId = UUID.randomUUID();
        String eventName = "test-event";
        String url = "/api/v1/sagas/" + sagaId + "/events/" + eventName;

        long timestamp = Instant.now().toEpochMilli();
        String body = "{\"status\":\"APPROVED\"}";
        String forgedSignature = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", forgedSignature);
        headers.set("X-Webhook-Timestamp", String.valueOf(timestamp));
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("message", "Invalid HMAC-SHA256 signature");
    }

    @Test
    @DisplayName("Webhook with valid signature and timestamp passes authentication (returns 404 for non-existent saga)")
    void testValidSignatureAuthentication() {
        UUID nonExistentSagaId = UUID.randomUUID();
        String eventName = "test-event";
        String url = "/api/v1/sagas/" + nonExistentSagaId + "/events/" + eventName;

        long timestamp = Instant.now().toEpochMilli();
        String body = "{\"status\":\"APPROVED\"}";
        String validSignature = securityService.computeHmacSha256(timestamp + "." + body, securityService.getDefaultSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Signature", validSignature);
        headers.set("X-Webhook-Timestamp", String.valueOf(timestamp));
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        // Signature passes authentication; fails subsequent check because sagaId does not exist
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "NOT_FOUND");
    }
}
