package io.deltaflow.audit.dto;

import java.time.Instant;

public record AuditExportRequest(
        Instant fromTimestamp,
        Instant toTimestamp,
        String tenantId,
        String eventType,
        AuditExportFormat format
) {
    public AuditExportFormat resolvedFormat() {
        return format != null ? format : AuditExportFormat.JSON;
    }
}
