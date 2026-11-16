package io.deltaflow.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.audit.dto.AuditExportFormat;
import io.deltaflow.audit.dto.AuditExportRecord;
import io.deltaflow.audit.dto.AuditExportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class AuditLogExporter {

    private static final Logger log = LoggerFactory.getLogger(AuditLogExporter.class);
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final AuditLogStore auditLogStore;
    private final PciDssMaskingService maskingService;
    private final ObjectMapper objectMapper;

    public AuditLogExporter(
            AuditLogStore auditLogStore,
            PciDssMaskingService maskingService,
            ObjectMapper objectMapper
    ) {
        this.auditLogStore = auditLogStore;
        this.maskingService = maskingService;
        this.objectMapper = objectMapper;
    }

    public List<AuditExportRecord> exportRecords(AuditExportRequest request) {
        List<AuditLogStore.RawAuditEntry> rawEntries = auditLogStore.findEvents(request);
        List<AuditExportRecord> records = new ArrayList<>();

        String currentPrevHash = GENESIS_HASH;
        long sequenceNumber = 1L;

        for (AuditLogStore.RawAuditEntry raw : rawEntries) {
            String maskedPayload = maskingService.mask(raw.rawPayload());
            String payloadHash = sha256(raw.rawPayload() != null ? raw.rawPayload() : "");
            String checksumData = currentPrevHash + ":" + raw.eventId() + ":" + raw.timestamp() + ":" + raw.eventType() + ":" + payloadHash;
            String recordChecksum = sha256(checksumData);

            AuditExportRecord record = new AuditExportRecord(
                    sequenceNumber++,
                    raw.eventId(),
                    raw.eventType(),
                    raw.tenantId(),
                    raw.idempotencyKey(),
                    raw.sagaId(),
                    raw.timestamp(),
                    raw.operatorId(),
                    maskedPayload,
                    payloadHash,
                    currentPrevHash,
                    recordChecksum
            );

            records.add(record);
            currentPrevHash = recordChecksum;
        }

        log.info("Exported {} chained audit records for request: {}", records.size(), request);
        return records;
    }

    public void streamExport(AuditExportRequest request, OutputStream outputStream) throws IOException {
        AuditExportFormat format = request != null ? request.resolvedFormat() : AuditExportFormat.JSON;
        List<AuditExportRecord> records = exportRecords(request);

        if (format == AuditExportFormat.CSV) {
            streamCsv(records, outputStream);
        } else {
            streamJson(records, outputStream);
        }
    }

    private void streamJson(List<AuditExportRecord> records, OutputStream outputStream) throws IOException {
        PrintWriter writer = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        for (AuditExportRecord record : records) {
            String jsonLine = objectMapper.writeValueAsString(record);
            writer.println(jsonLine);
        }
        writer.flush();
    }

    private void streamCsv(List<AuditExportRecord> records, OutputStream outputStream) {
        PrintWriter writer = new PrintWriter(outputStream, true, StandardCharsets.UTF_8);
        writer.println("sequenceNumber,eventId,eventType,tenantId,idempotencyKey,sagaId,timestamp,operatorId,maskedPayload,payloadHash,previousEventHash,recordChecksum");

        for (AuditExportRecord r : records) {
            String escapedPayload = escapeCsvField(r.maskedPayload());
            writer.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    r.sequenceNumber(),
                    r.eventId(),
                    r.eventType(),
                    safe(r.tenantId()),
                    safe(r.idempotencyKey()),
                    safe(r.sagaId()),
                    r.timestamp(),
                    safe(r.operatorId()),
                    escapedPayload,
                    r.payloadHash(),
                    r.previousEventHash(),
                    r.recordChecksum()
            );
        }
        writer.flush();
    }

    public boolean verifyChainIntegrity(List<AuditExportRecord> records) {
        if (records == null || records.isEmpty()) {
            return true;
        }

        String expectedPrevHash = GENESIS_HASH;
        long expectedSeq = 1;

        for (AuditExportRecord r : records) {
            if (r.sequenceNumber() != expectedSeq++) {
                return false;
            }
            if (!r.previousEventHash().equals(expectedPrevHash)) {
                return false;
            }
            String calculatedChecksum = sha256(expectedPrevHash + ":" + r.eventId() + ":" + r.timestamp() + ":" + r.eventType() + ":" + r.payloadHash());
            if (!calculatedChecksum.equals(r.recordChecksum())) {
                return false;
            }
            expectedPrevHash = r.recordChecksum();
        }
        return true;
    }

    private static String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }

    private static String safe(String val) {
        return val != null ? val : "";
    }

    private static String escapeCsvField(String field) {
        if (field == null) {
            return "\"\"";
        }
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }
}
