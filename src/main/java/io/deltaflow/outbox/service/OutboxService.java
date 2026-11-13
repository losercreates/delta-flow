package io.deltaflow.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.outbox.domain.OutboxEvent;
import io.deltaflow.outbox.domain.OutboxStatus;
import io.deltaflow.outbox.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogger auditLogger;

    public OutboxService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            AuditLogger auditLogger
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.auditLogger = auditLogger;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public OutboxEvent recordEvent(String aggregateType, String aggregateId, String eventType, Object payload) {
        return recordEvent(aggregateType, aggregateId, eventType, payload, "default", aggregateId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public OutboxEvent recordEvent(
            String aggregateType,
            String aggregateId,
            String eventType,
            Object payload,
            String tenantId,
            String idempotencyKey
    ) {
        String payloadJson;
        if (payload instanceof String s) {
            payloadJson = s;
        } else {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize outbox event payload", e);
                payloadJson = "{\"error\":\"Serialization failure: " + e.getMessage() + "\"}";
            }
        }

        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payloadJson);
        event.setStatus(OutboxStatus.PENDING.name());
        event.setCreatedAt(Instant.now());

        OutboxEvent saved = outboxEventRepository.save(event);
        log.info("Persisted transactional outbox event [id: {}, type: {}, aggregate: {}:{}]",
                eventId, eventType, aggregateType, aggregateId);

        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("eventId", eventId.toString());
        auditDetails.put("aggregateType", aggregateType);
        auditDetails.put("aggregateId", aggregateId);
        auditDetails.put("eventType", eventType);
        auditLogger.logEvent("OUTBOX_RECORDED", tenantId, idempotencyKey, auditDetails);

        return saved;
    }
}

