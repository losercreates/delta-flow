package io.deltaflow.cluster;

import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.cluster.domain.RetentionSummaryDto;
import io.deltaflow.cluster.service.IdempotencyRetentionService;
import io.deltaflow.idempotency.domain.IdempotencyRecord;
import io.deltaflow.idempotency.domain.IdempotencyRecordId;
import io.deltaflow.idempotency.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Automated Idempotency Retention Pruning Integration Test")
class IdempotencyRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private IdempotencyRetentionService retentionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TENANT = "tenant-retention-test";

    @BeforeEach
    void setUp() {
        idempotencyRecordRepository.deleteAll();
    }

    @Test
    @DisplayName("Should prune terminal RESOLVED records older than retention cutoff in bounded batches")
    void shouldPruneExpiredResolvedRecords() {
        // 1. Seed expired RESOLVED record (created 35 days ago)
        createRecordWithPastCreatedAt("key-expired-1", "RESOLVED", 35);
        createRecordWithPastCreatedAt("key-expired-2", "RESOLVED", 40);

        // 2. Seed recent RESOLVED record (created 5 days ago) - MUST BE PRESERVED
        createRecordWithPastCreatedAt("key-recent-1", "RESOLVED", 5);

        // 3. Seed active PROCESSING record (created 35 days ago) - MUST BE STRICTLY PRESERVED (Principle I)
        createRecordWithPastCreatedAt("key-active-lock", "PROCESSING", 35);

        // When retention pruning runs with a 30-day window
        RetentionSummaryDto summary = retentionService.pruneExpiredRecords(30, 10, "test-operator", "Retention test sweep");

        // Then 2 expired records are deleted
        assertThat(summary.totalPrunedRecords()).isEqualTo(2);

        // Verify remaining records in database
        assertThat(idempotencyRecordRepository.findById(TENANT, "key-expired-1")).isEmpty();
        assertThat(idempotencyRecordRepository.findById(TENANT, "key-expired-2")).isEmpty();

        // Recent resolved record still exists
        assertThat(idempotencyRecordRepository.findById(TENANT, "key-recent-1")).isPresent();

        // Active PROCESSING lock still exists (100% preservation)
        assertThat(idempotencyRecordRepository.findById(TENANT, "key-active-lock")).isPresent();
        assertThat(idempotencyRecordRepository.findById(TENANT, "key-active-lock").get().getStatus())
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("Should preserve active PROCESSING records regardless of age")
    void shouldStrictlyPreserveActiveProcessingRecords() {
        // Seed active PROCESSING lock created 60 days ago
        createRecordWithPastCreatedAt("key-ancient-lock", "PROCESSING", 60);

        RetentionSummaryDto summary = retentionService.pruneExpiredRecords(30, 500, "operator-audit", "Preserve active test");

        assertThat(summary.totalPrunedRecords()).isEqualTo(0);
        assertThat(idempotencyRecordRepository.findById(TENANT, "key-ancient-lock")).isPresent();
    }

    private void createRecordWithPastCreatedAt(String idempotencyKey, String status, int daysAgo) {
        Instant pastInstant = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        java.sql.Timestamp pastTs = java.sql.Timestamp.from(pastInstant);
        java.sql.Timestamp leaseTs = status.equals("PROCESSING") 
                ? java.sql.Timestamp.from(pastInstant.plusSeconds(300)) 
                : pastTs;

        // Insert record directly via JdbcTemplate to bypass entity @PrePersist timestamp overriding
        jdbcTemplate.update("""
            INSERT INTO idempotency_records 
                (tenant_id, idempotency_key, request_hash, status, lease_expires_at, created_at, updated_at) 
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, TENANT, idempotencyKey, "hash-" + idempotencyKey, status, leaseTs, pastTs, pastTs);
    }
}
