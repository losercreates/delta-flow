package io.deltaflow.saga.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_ingress_records", indexes = {
    @Index(name = "idx_webhook_records_saga", columnList = "saga_id, event_name")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_webhook_idempotency", columnNames = {"tenant_id", "idempotency_key"})
})
public class WebhookIngressRecord {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "event_name", length = 128, nullable = false)
    private String eventName;

    @Column(name = "idempotency_key", length = 256, nullable = false)
    private String idempotencyKey;

    @Column(name = "payload_hash", length = 64, nullable = false)
    private String payloadHash;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public WebhookIngressRecord() {
    }

    public WebhookIngressRecord(UUID id, UUID sagaId, String tenantId, String eventName,
                                String idempotencyKey, String payloadHash, boolean signatureValid,
                                String status, String errorMessage) {
        this.id = id != null ? id : UUID.randomUUID();
        this.sagaId = sagaId;
        this.tenantId = tenantId != null ? tenantId : "default";
        this.eventName = eventName;
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.signatureValid = signatureValid;
        this.status = status;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSagaId() {
        return sagaId;
    }

    public void setSagaId(UUID sagaId) {
        this.sagaId = sagaId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public void setSignatureValid(boolean signatureValid) {
        this.signatureValid = signatureValid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
