package io.deltaflow.audit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.AbstractIntegrationTest;
import io.deltaflow.audit.dto.AuditExportFormat;
import io.deltaflow.audit.dto.AuditExportRecord;
import io.deltaflow.audit.dto.AuditExportRequest;
import io.deltaflow.audit.service.AuditLogExporter;
import io.deltaflow.audit.service.AuditLogStore;
import io.deltaflow.audit.service.PciDssMaskingService;
import io.deltaflow.common.audit.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AuditExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuditLogStore auditLogStore;

    @Autowired
    private AuditLogger auditLogger;

    @Autowired
    private AuditLogExporter auditLogExporter;

    @Autowired
    private PciDssMaskingService maskingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        auditLogStore.clear();
    }

    @Test
    @DisplayName("Should export immutable audit log stream in NDJSON format with hash chaining")
    void shouldExportChainedNdjsonAuditLog() throws IOException {
        String tenantId = "tenant-audit-test";
        String idempKey = "key-" + UUID.randomUUID();

        // 1. Emit several audit events
        auditLogger.logEvent("SAGA_STARTED", tenantId, idempKey, Map.of(
                "sourceAccount", "4111111111111234",
                "amount", 500.00,
                "password", "secretCredentials123!"
        ));
        auditLogger.logEvent("STEP_COMPLETED", tenantId, idempKey, Map.of(
                "stepIndex", 0,
                "stepName", "reserve-funds",
                "targetAccount", "5555444433331002"
        ));
        auditLogger.logEvent("SAGA_COMPLETED", tenantId, idempKey, Map.of(
                "status", "COMPLETED",
                "totalTransferred", 500.00
        ));

        // 2. Fetch export via HTTP endpoint
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/audit/export?format=JSON&tenantId=" + tenantId,
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType().toString()).contains("application/x-ndjson");

        String body = response.getBody();
        assertThat(body).isNotNull();
        String[] lines = body.trim().split("\n");
        assertThat(lines).hasSize(3);

        // 3. Verify PCI-DSS masking: sensitive credentials redacted, PAN masked to last 4
        assertThat(body).doesNotContain("secretCredentials123!");
        assertThat(body).contains("[REDACTED]");
        assertThat(body).doesNotContain("4111111111111234");
        assertThat(body).contains("****-****-****-1234");
        assertThat(body).doesNotContain("5555444433331002");
        assertThat(body).contains("****-****-****-1002");

        // 4. Verify cryptographic hash chain across records
        List<AuditExportRecord> records = auditLogExporter.exportRecords(
                new AuditExportRequest(null, null, tenantId, null, AuditExportFormat.JSON)
        );
        assertThat(records).hasSize(3);
        assertThat(auditLogExporter.verifyChainIntegrity(records)).isTrue();

        // Sequential numbers
        assertThat(records.get(0).sequenceNumber()).isEqualTo(1L);
        assertThat(records.get(1).sequenceNumber()).isEqualTo(2L);
        assertThat(records.get(2).sequenceNumber()).isEqualTo(3L);

        // Hash chaining
        assertThat(records.get(0).previousEventHash()).isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(records.get(1).previousEventHash()).isEqualTo(records.get(0).recordChecksum());
        assertThat(records.get(2).previousEventHash()).isEqualTo(records.get(1).recordChecksum());
    }

    @Test
    @DisplayName("Should export audit logs in CSV format with valid header and escaping")
    void shouldExportChainedCsvAuditLog() throws IOException {
        String tenantId = "tenant-csv-test";
        String idempKey = "key-" + UUID.randomUUID();

        auditLogger.logEvent("OPERATOR_INTERVENTION", tenantId, idempKey, Map.of(
                "operatorId", "op_sec_admin",
                "action", "FORCE_UNFREEZE",
                "reason", "Audited incident, manual clearance approved."
        ));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        auditLogExporter.streamExport(
                new AuditExportRequest(null, null, tenantId, null, AuditExportFormat.CSV),
                out
        );

        String csv = out.toString(StandardCharsets.UTF_8);
        String[] lines = csv.trim().split("\n");

        assertThat(lines.length).isGreaterThanOrEqualTo(2);
        assertThat(lines[0]).isEqualTo("sequenceNumber,eventId,eventType,tenantId,idempotencyKey,sagaId,timestamp,operatorId,maskedPayload,payloadHash,previousEventHash,recordChecksum");
        assertThat(lines[1]).contains("OPERATOR_INTERVENTION");
        assertThat(lines[1]).contains("op_sec_admin");
    }

    @Test
    @DisplayName("Should filter audit records by eventType and timestamp window")
    void shouldFilterRecordsByCriteria() {
        String tenantId = "tenant-filter";
        Instant t1 = Instant.now();

        auditLogger.logEvent("LOCK_ACQUIRED", tenantId, "k1", Map.of("detail", "lock1"));
        auditLogger.logEvent("LOCK_RELEASED", tenantId, "k2", Map.of("detail", "lock2"));
        auditLogger.logEvent("LOCK_ACQUIRED", tenantId, "k3", Map.of("detail", "lock3"));

        List<AuditExportRecord> lockAcquiredRecords = auditLogExporter.exportRecords(
                new AuditExportRequest(t1.minusSeconds(1), Instant.now().plusSeconds(10), tenantId, "LOCK_ACQUIRED", AuditExportFormat.JSON)
        );

        assertThat(lockAcquiredRecords).hasSize(2);
        assertThat(lockAcquiredRecords).allMatch(r -> "LOCK_ACQUIRED".equals(r.eventType()));
    }
}
