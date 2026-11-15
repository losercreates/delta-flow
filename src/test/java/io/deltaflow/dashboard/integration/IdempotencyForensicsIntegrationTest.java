package io.deltaflow.dashboard.integration;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.dashboard.dto.IdempotencyForensicDto;
import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.domain.IdempotencyRecordId;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class IdempotencyForensicsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    @DisplayName("US2: Query idempotency records by key, payload hash, and status")
    void testSearchIdempotencyRecords() {
        String key1 = "forensic-key-" + UUID.randomUUID();
        String hash1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        IdempotencyRecord rec1 = new IdempotencyRecord(
                new IdempotencyRecordId("tenant-forensic-1", key1),
                hash1,
                "RESOLVED",
                Instant.now().plus(60, ChronoUnit.SECONDS)
        );
        rec1.setResponseStatusCode(200);
        rec1.setResponsePayload("{\"status\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8));
        idempotencyRecordRepository.save(rec1);

        String key2 = "forensic-key-" + UUID.randomUUID();
        String hash2 = "a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0";
        IdempotencyRecord rec2 = new IdempotencyRecord(
                new IdempotencyRecordId("tenant-forensic-2", key2),
                hash2,
                "PROCESSING",
                Instant.now().plus(30, ChronoUnit.SECONDS)
        );
        idempotencyRecordRepository.save(rec2);

        // 1. Search by key substring
        ResponseEntity<List<IdempotencyForensicDto>> responseByKey = restTemplate.exchange(
                "/api/v1/dashboard/idempotency?key=" + key1,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<IdempotencyForensicDto>>() {}
        );

        assertThat(responseByKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<IdempotencyForensicDto> resultsByKey = responseByKey.getBody();
        assertThat(resultsByKey).isNotNull();
        assertThat(resultsByKey).hasSize(1);
        assertThat(resultsByKey.get(0).idempotencyKey()).isEqualTo(key1);
        assertThat(resultsByKey.get(0).payloadHash()).isEqualTo(hash1);
        assertThat(resultsByKey.get(0).cachedResponse()).isEqualTo("{\"status\":\"SUCCESS\"}");
        assertThat(resultsByKey.get(0).statusCode()).isEqualTo(200);

        // 2. Search by payload hash
        ResponseEntity<List<IdempotencyForensicDto>> responseByHash = restTemplate.exchange(
                "/api/v1/dashboard/idempotency?payloadHash=" + hash2,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<IdempotencyForensicDto>>() {}
        );

        assertThat(responseByHash.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<IdempotencyForensicDto> resultsByHash = responseByHash.getBody();
        assertThat(resultsByHash).isNotNull();
        assertThat(resultsByHash).hasSize(1);
        assertThat(resultsByHash.get(0).idempotencyKey()).isEqualTo(key2);
        assertThat(resultsByHash.get(0).status()).isEqualTo("PROCESSING");

        // 3. Search by tenant and status
        ResponseEntity<List<IdempotencyForensicDto>> responseByTenant = restTemplate.exchange(
                "/api/v1/dashboard/idempotency?tenantId=tenant-forensic-1&status=RESOLVED",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<IdempotencyForensicDto>>() {}
        );

        assertThat(responseByTenant.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<IdempotencyForensicDto> resultsByTenant = responseByTenant.getBody();
        assertThat(resultsByTenant).isNotNull();
        assertThat(resultsByTenant).anyMatch(r -> r.idempotencyKey().equals(key1));
    }
}
