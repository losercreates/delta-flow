package io.deltaflow.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.audit.dto.AuditExportFormat;
import io.deltaflow.audit.dto.AuditExportRecord;
import io.deltaflow.audit.dto.AuditExportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AuditLogExporterTest {

    private AuditLogStore auditLogStore;
    private PciDssMaskingService maskingService;
    private ObjectMapper objectMapper;
    private AuditLogExporter exporter;

    @BeforeEach
    void setUp() {
        auditLogStore = new AuditLogStore();
        maskingService = new PciDssMaskingService();
        objectMapper = new ObjectMapper();
        exporter = new AuditLogExporter(auditLogStore, maskingService, objectMapper);
    }

    @Test
    @DisplayName("Should generate mathematically valid cryptographic hash chain across records")
    void shouldGenerateValidHashChain() {
        UUID event1 = UUID.randomUUID();
        UUID event2 = UUID.randomUUID();
        UUID event3 = UUID.randomUUID();
        Instant now = Instant.now();

        auditLogStore.record(new AuditLogStore.RawAuditEntry(
                event1, "SAGA_STARTED", "tenant-1", "key-1", null, now, null,
                "{\"sourceAccount\":\"4111222233334444\",\"amount\":100}"
        ));
        auditLogStore.record(new AuditLogStore.RawAuditEntry(
                event2, "STEP_COMPLETED", "tenant-1", "key-1", null, now.plusMillis(10), null,
                "{\"step\":0,\"status\":\"OK\"}"
        ));
        auditLogStore.record(new AuditLogStore.RawAuditEntry(
                event3, "SAGA_COMPLETED", "tenant-1", "key-1", null, now.plusMillis(20), null,
                "{\"status\":\"COMPLETED\"}"
        ));

        List<AuditExportRecord> exported = exporter.exportRecords(
                new AuditExportRequest(null, null, "tenant-1", null, AuditExportFormat.JSON)
        );

        assertThat(exported).hasSize(3);
        assertThat(exporter.verifyChainIntegrity(exported)).isTrue();

        // Sequence numbers
        assertThat(exported.get(0).sequenceNumber()).isEqualTo(1L);
        assertThat(exported.get(1).sequenceNumber()).isEqualTo(2L);
        assertThat(exported.get(2).sequenceNumber()).isEqualTo(3L);

        // Previous hashes
        assertThat(exported.get(0).previousEventHash()).isEqualTo("0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(exported.get(1).previousEventHash()).isEqualTo(exported.get(0).recordChecksum());
        assertThat(exported.get(2).previousEventHash()).isEqualTo(exported.get(1).recordChecksum());
    }

    @Test
    @DisplayName("Chain integrity verification should detect tampering or payload alterations")
    void shouldDetectTampering() {
        UUID event1 = UUID.randomUUID();
        UUID event2 = UUID.randomUUID();
        Instant now = Instant.now();

        auditLogStore.record(new AuditLogStore.RawAuditEntry(
                event1, "LOCK_ACQUIRED", "tenant-1", "k1", null, now, null, "{\"lock\":\"res1\"}"
        ));
        auditLogStore.record(new AuditLogStore.RawAuditEntry(
                event2, "LOCK_RELEASED", "tenant-1", "k1", null, now.plusMillis(5), null, "{\"lock\":\"res1\"}"
        ));

        List<AuditExportRecord> original = exporter.exportRecords(new AuditExportRequest(null, null, null, null, AuditExportFormat.JSON));
        assertThat(exporter.verifyChainIntegrity(original)).isTrue();

        // Tamper with record 1 payload
        List<AuditExportRecord> tampered = new ArrayList<>();
        AuditExportRecord r0 = original.get(0);
        AuditExportRecord tamperedR0 = new AuditExportRecord(
                r0.sequenceNumber(), r0.eventId(), r0.eventType(), r0.tenantId(), r0.idempotencyKey(),
                r0.sagaId(), r0.timestamp(), r0.operatorId(), "TAMPERED_PAYLOAD", "bad_hash",
                r0.previousEventHash(), r0.recordChecksum()
        );
        tampered.add(tamperedR0);
        tampered.add(original.get(1));

        assertThat(exporter.verifyChainIntegrity(tampered)).isFalse();
    }

    @Test
    @DisplayName("Should stream valid CSV output with properly escaped fields")
    void shouldStreamCsv() throws IOException {
        auditLogStore.record(new AuditLogStore.RawAuditEntry(
                UUID.randomUUID(), "OPERATOR_INTERVENTION", "t1", "k1", null, Instant.now(), "admin",
                "{\"reason\":\"Manual override, approved by auditor\"}"
        ));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.streamExport(new AuditExportRequest(null, null, "t1", null, AuditExportFormat.CSV), out);

        String csv = out.toString(StandardCharsets.UTF_8);
        String[] lines = csv.trim().split("\n");
        assertThat(lines.length).isGreaterThanOrEqualTo(2);
        assertThat(lines[0]).startsWith("sequenceNumber,eventId,eventType");
        assertThat(lines[1]).contains("OPERATOR_INTERVENTION");
        assertThat(lines[1]).contains("admin");
    }
}
