package io.deltaflow.saga.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.deltaflow.common.audit.AuditLogger;
import io.deltaflow.idempotency.service.IdempotencyCoordinator;
import io.deltaflow.saga.domain.SagaInstance;
import io.deltaflow.saga.domain.WebhookIngressRecord;
import io.deltaflow.saga.exception.SagaCompensatedException;
import io.deltaflow.saga.repository.SagaInstanceRepository;
import io.deltaflow.saga.repository.WebhookIngressRepository;
import io.deltaflow.saga.service.SagaCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/sagas")
public class WebhookIngressController {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngressController.class);

    private final SagaCoordinator sagaCoordinator;
    private final WebhookSecurityService securityService;
    private final WebhookIngressRepository webhookIngressRepository;
    private final IdempotencyCoordinator idempotencyCoordinator;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public WebhookIngressController(
            SagaCoordinator sagaCoordinator,
            WebhookSecurityService securityService,
            WebhookIngressRepository webhookIngressRepository,
            IdempotencyCoordinator idempotencyCoordinator,
            SagaInstanceRepository sagaInstanceRepository,
            AuditLogger auditLogger,
            ObjectMapper objectMapper
    ) {
        this.sagaCoordinator = sagaCoordinator;
        this.securityService = securityService;
        this.webhookIngressRepository = webhookIngressRepository;
        this.idempotencyCoordinator = idempotencyCoordinator;
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{sagaId}/events/{eventName}")
    public ResponseEntity<Map<String, Object>> deliverWebhookEvent(
            @PathVariable("sagaId") UUID sagaId,
            @PathVariable("eventName") String eventName,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signatureHeader,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) Long timestampHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestHeader(value = "X-Tenant-ID", required = false, defaultValue = "default") String tenantId,
            @RequestBody(required = false) String rawBody
    ) {
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        String body = rawBody != null ? rawBody : "";

        // 1. Validate security headers presence
        if (signatureHeader == null || signatureHeader.isBlank() || timestampHeader == null) {
            log.warn("Webhook rejected for saga [{}]: missing security headers", sagaId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse(
                    HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Missing required security headers (X-Webhook-Signature, X-Webhook-Timestamp)"));
        }

        // 2. Validate timestamp freshness (Anti-replay window)
        if (!securityService.verifyTimestamp(timestampHeader)) {
            log.warn("Webhook rejected for saga [{}]: timestamp expired or outside tolerance: {}", sagaId, timestampHeader);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse(
                    HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Webhook timestamp expired or outside tolerance window"));
        }

        // 3. Verify HMAC-SHA256 signature
        if (!securityService.verifySignature(body, timestampHeader, signatureHeader)) {
            log.warn("Webhook rejected for saga [{}]: invalid HMAC signature", sagaId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse(
                    HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid HMAC-SHA256 signature"));
        }

        String payloadHash = computeSha256(body);
        String idempotencyKey = (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank())
                ? idempotencyKeyHeader
                : "webhook-" + sagaId + "-" + eventName + "-" + payloadHash.substring(0, 8);

        // 4. Check idempotency deduplication in ingress records
        Optional<WebhookIngressRecord> existingRecordOpt = webhookIngressRepository.findByTenantIdAndIdempotencyKey(effectiveTenant, idempotencyKey);
        if (existingRecordOpt.isPresent()) {
            WebhookIngressRecord existing = existingRecordOpt.get();
            if ("COMPLETED".equals(existing.getStatus())) {
                log.info("Idempotent webhook replay for saga [{}], key [{}]", sagaId, idempotencyKey);
                auditLogger.logEvent("WEBHOOK_REPLAYED", effectiveTenant, idempotencyKey, Map.of("sagaId", sagaId.toString(), "eventName", eventName));
                return ResponseEntity.ok(Map.of(
                        "sagaId", sagaId.toString(),
                        "eventName", eventName,
                        "status", "REPLAYED",
                        "processedAt", existing.getCreatedAt().toString()
                ));
            } else if ("REJECTED_ZOMBIE".equals(existing.getStatus())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse(
                        HttpStatus.CONFLICT, "CONFLICT", existing.getErrorMessage() != null ? existing.getErrorMessage() : "Zombie webhook previously rejected"));
            }
        }

        // 5. State validation against target SagaInstance
        Optional<SagaInstance> sagaOpt = sagaInstanceRepository.findById(sagaId);
        if (sagaOpt.isEmpty()) {
            log.warn("Webhook target saga [{}] not found", sagaId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse(
                    HttpStatus.NOT_FOUND, "NOT_FOUND", "Saga instance not found: " + sagaId));
        }

        SagaInstance instance = sagaOpt.get();
        String sagaStatus = instance.getStatus();

        if ("COMPLETED".equals(sagaStatus)) {
            return ResponseEntity.ok(Map.of(
                    "sagaId", sagaId.toString(),
                    "eventName", eventName,
                    "status", "COMPLETED",
                    "processedAt", Instant.now().toString()
            ));
        }

        if ("TIMED_OUT".equals(sagaStatus) || "COMPENSATING".equals(sagaStatus)
                || "COMPENSATED".equals(sagaStatus) || "FAILED".equals(sagaStatus)) {
            String errorMsg = "Saga " + sagaId + " is in terminal/compensating state: " + sagaStatus;
            recordIngressOutcome(sagaId, effectiveTenant, eventName, idempotencyKey, payloadHash, "REJECTED_ZOMBIE", errorMsg);
            auditLogger.logEvent("ZOMBIE_WEBHOOK_REJECTED", effectiveTenant, idempotencyKey, Map.of(
                    "sagaId", sagaId.toString(),
                    "status", sagaStatus
            ));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse(
                    HttpStatus.CONFLICT, "CONFLICT", errorMsg));
        }

        if (!"SUSPENDED".equals(sagaStatus)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse(
                    HttpStatus.CONFLICT, "CONFLICT", "Saga is not in SUSPENDED state; current status: " + sagaStatus));
        }

        if (instance.getAwaitingEventName() != null && !instance.getAwaitingEventName().equals(eventName)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse(
                    HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Saga is not awaiting event: " + eventName + "; expected: " + instance.getAwaitingEventName()));
        }

        // 6. Parse payload
        Object payloadObj = parsePayload(body);

        // 7. Track ingress record as PROCESSING
        WebhookIngressRecord ingressRecord = new WebhookIngressRecord(
                UUID.randomUUID(),
                sagaId,
                effectiveTenant,
                eventName,
                idempotencyKey,
                payloadHash,
                true,
                "PROCESSING",
                null
        );
        webhookIngressRepository.save(ingressRecord);

        // 8. Resume Saga via Coordinator
        try {
            sagaCoordinator.resumeSaga(sagaId, effectiveTenant, eventName, payloadObj);

            ingressRecord.setStatus("COMPLETED");
            webhookIngressRepository.save(ingressRecord);

            auditLogger.logEvent("WEBHOOK_PROCESSED", effectiveTenant, idempotencyKey, Map.of(
                    "sagaId", sagaId.toString(),
                    "eventName", eventName,
                    "status", "RESUMED"
            ));

            return ResponseEntity.ok(Map.of(
                    "sagaId", sagaId.toString(),
                    "eventName", eventName,
                    "status", "RESUMED",
                    "processedAt", Instant.now().toString()
            ));
        } catch (SagaCompensatedException e) {
            log.warn("Saga [{}] was compensated upon webhook delivery: {}", sagaId, e.getMessage());
            ingressRecord.setStatus("COMPENSATED");
            ingressRecord.setErrorMessage(e.getMessage());
            webhookIngressRepository.save(ingressRecord);

            auditLogger.logEvent("WEBHOOK_COMPENSATED", effectiveTenant, idempotencyKey, Map.of(
                    "sagaId", sagaId.toString(),
                    "eventName", eventName,
                    "error", e.getMessage()
            ));

            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorResponse(
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE_ENTITY", "Webhook event rejected, saga compensated: " + e.getMessage()));
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("ZOMBIE")) {
                ingressRecord.setStatus("REJECTED_ZOMBIE");
                ingressRecord.setErrorMessage(msg);
                webhookIngressRepository.save(ingressRecord);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse(
                        HttpStatus.CONFLICT, "CONFLICT", msg));
            }
            ingressRecord.setStatus("FAILED");
            ingressRecord.setErrorMessage(msg);
            webhookIngressRepository.save(ingressRecord);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", msg));
        } catch (Exception e) {
            log.error("Failed to resume saga [{}] with webhook event [{}]: {}", sagaId, eventName, e.getMessage(), e);
            ingressRecord.setStatus("FAILED");
            ingressRecord.setErrorMessage(e.getMessage());
            webhookIngressRepository.save(ingressRecord);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", e.getMessage()));
        }
    }

    private void recordIngressOutcome(UUID sagaId, String tenantId, String eventName, String idempotencyKey,
                                      String payloadHash, String status, String errorMessage) {
        try {
            WebhookIngressRecord rec = new WebhookIngressRecord(
                    UUID.randomUUID(), sagaId, tenantId, eventName, idempotencyKey, payloadHash, true, status, errorMessage
            );
            webhookIngressRepository.save(rec);
        } catch (Exception ignored) {}
    }

    private Object parsePayload(String body) {
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception e) {
            return Map.of("rawBody", body);
        }
    }

    private String computeSha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(data.hashCode());
        }
    }

    private Map<String, Object> errorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", Instant.now().toString());
        resp.put("status", status.value());
        resp.put("error", error);
        resp.put("message", message);
        return resp;
    }
}
