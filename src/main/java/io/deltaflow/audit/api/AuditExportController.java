package io.deltaflow.audit.api;

import io.deltaflow.audit.dto.AuditExportFormat;
import io.deltaflow.audit.dto.AuditExportRequest;
import io.deltaflow.audit.service.AuditLogExporter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditExportController {

    private final AuditLogExporter auditLogExporter;

    public AuditExportController(AuditLogExporter auditLogExporter) {
        this.auditLogExporter = auditLogExporter;
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> exportAuditTrail(
            @RequestParam(name = "format", defaultValue = "JSON") String formatStr,
            @RequestParam(name = "fromTimestamp", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromTimestamp,
            @RequestParam(name = "toTimestamp", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toTimestamp,
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "eventType", required = false) String eventType
    ) {
        AuditExportFormat format;
        try {
            format = AuditExportFormat.valueOf(formatStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            format = AuditExportFormat.JSON;
        }

        AuditExportRequest request = new AuditExportRequest(fromTimestamp, toTimestamp, tenantId, eventType, format);

        String dateSuffix = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = format == AuditExportFormat.CSV
                ? "deltaflow-audit-trail-" + dateSuffix + ".csv"
                : "deltaflow-audit-trail-" + dateSuffix + ".jsonl";
        MediaType mediaType = format == AuditExportFormat.CSV
                ? MediaType.parseMediaType("text/csv")
                : MediaType.parseMediaType("application/x-ndjson");

        StreamingResponseBody responseBody = outputStream -> auditLogExporter.streamExport(request, outputStream);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(responseBody);
    }
}
