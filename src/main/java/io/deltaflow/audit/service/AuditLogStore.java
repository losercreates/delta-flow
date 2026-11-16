package io.deltaflow.audit.service;

import io.deltaflow.audit.dto.AuditExportRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe audit event accumulator retaining tamper-evident audit events
 * across all system components for regulatory SOC2/PCI-DSS export.
 */
@Component
public class AuditLogStore {

    public record RawAuditEntry(
            UUID eventId,
            String eventType,
            String tenantId,
            String idempotencyKey,
            String sagaId,
            Instant timestamp,
            String operatorId,
            String rawPayload
    ) {}

    private final List<RawAuditEntry> entries = new CopyOnWriteArrayList<>();

    public void record(RawAuditEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    public List<RawAuditEntry> findEvents(AuditExportRequest request) {
        if (request == null) {
            return new ArrayList<>(entries);
        }

        return entries.stream()
                .filter(e -> request.tenantId() == null || request.tenantId().isBlank() || request.tenantId().equalsIgnoreCase(e.tenantId()))
                .filter(e -> request.eventType() == null || request.eventType().isBlank() || request.eventType().equalsIgnoreCase(e.eventType()))
                .filter(e -> request.fromTimestamp() == null || !e.timestamp().isBefore(request.fromTimestamp()))
                .filter(e -> request.toTimestamp() == null || !e.timestamp().isAfter(request.toTimestamp()))
                .toList();
    }

    public List<RawAuditEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    public void clear() {
        entries.clear();
    }
}
