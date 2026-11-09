package io.deltaflow.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured JSON audit logger emitting immutable events for SOC2/PCI-DSS compliance.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("io.deltaflow.audit");
    private final ObjectMapper objectMapper;

    public AuditLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void logEvent(String eventType, String tenantId, String idempotencyKey, Map<String, Object> details) {
        Map<String, Object> auditRecord = new LinkedHashMap<>();
        auditRecord.put("timestamp", Instant.now().toString());
        auditRecord.put("eventType", eventType);
        auditRecord.put("tenantId", tenantId);
        auditRecord.put("idempotencyKey", idempotencyKey);
        if (details != null && !details.isEmpty()) {
            auditRecord.put("details", details);
        }

        try {
            log.info(objectMapper.writeValueAsString(auditRecord));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit log for key: {}, falling back to toString", idempotencyKey, e);
            log.info("{}", auditRecord);
        }
    }
}

