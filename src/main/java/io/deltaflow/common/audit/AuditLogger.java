package io.deltaflow.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Structured JSON audit logger emitting immutable events for SOC2/PCI-DSS compliance.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("io.deltaflow.audit");
    private final ObjectMapper objectMapper;
    private final io.deltaflow.audit.service.AuditLogStore auditLogStore;

    public AuditLogger(
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false) io.deltaflow.audit.service.AuditLogStore auditLogStore
    ) {
        this.objectMapper = objectMapper;
        this.auditLogStore = auditLogStore;
    }

    public AuditLogger(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public void logEvent(String eventType, String tenantId, String idempotencyKey, Map<String, Object> details) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        String sagaId = null;
        String operatorId = null;

        if (details != null) {
            if (details.containsKey("sagaId")) {
                sagaId = String.valueOf(details.get("sagaId"));
            }
            if (details.containsKey("operatorId")) {
                operatorId = String.valueOf(details.get("operatorId"));
            }
            if (details.containsKey("eventId")) {
                try {
                    eventId = UUID.fromString(String.valueOf(details.get("eventId")));
                } catch (Exception ignored) {}
            }
        }

        Map<String, Object> auditRecord = new LinkedHashMap<>();
        auditRecord.put("eventId", eventId.toString());
        auditRecord.put("timestamp", now.toString());
        auditRecord.put("eventType", eventType);
        auditRecord.put("tenantId", tenantId);
        auditRecord.put("idempotencyKey", idempotencyKey);
        if (sagaId != null) {
            auditRecord.put("sagaId", sagaId);
        }
        if (operatorId != null) {
            auditRecord.put("operatorId", operatorId);
        }
        if (details != null && !details.isEmpty()) {
            auditRecord.put("details", details);
        }

        String serializedPayload;
        try {
            serializedPayload = objectMapper.writeValueAsString(auditRecord);
            log.info(serializedPayload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit log for key: {}, falling back to toString", idempotencyKey, e);
            serializedPayload = auditRecord.toString();
            log.info("{}", serializedPayload);
        }

        if (auditLogStore != null) {
            auditLogStore.record(new io.deltaflow.audit.service.AuditLogStore.RawAuditEntry(
                    eventId,
                    eventType,
                    tenantId,
                    idempotencyKey,
                    sagaId,
                    now,
                    operatorId,
                    serializedPayload
            ));
        }
    }
}

