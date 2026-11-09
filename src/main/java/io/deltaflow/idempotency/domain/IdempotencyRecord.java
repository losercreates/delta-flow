package io.deltaflow.idempotency.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_records", indexes = {
    @Index(name = "idx_idempotency_lease_expiry", columnList = "status, lease_expires_at"),
    @Index(name = "idx_idempotency_created_at", columnList = "created_at")
})
public class IdempotencyRecord {

    @EmbeddedId
    private IdempotencyRecordId id;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "lock_token", length = 64)
    private String lockToken;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "response_headers", columnDefinition = "jsonb")
    private String responseHeaders;

    @Lob
    @Column(name = "response_payload")
    private byte[] responsePayload;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(IdempotencyRecordId id, String requestHash, String status, Instant leaseExpiresAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.status = status;
        this.leaseExpiresAt = leaseExpiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and Setters

    public IdempotencyRecordId getId() {
        return id;
    }

    public void setId(IdempotencyRecordId id) {
        this.id = id;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLockToken() {
        return lockToken;
    }

    public void setLockToken(String lockToken) {
        this.lockToken = lockToken;
    }

    public Integer getResponseStatusCode() {
        return responseStatusCode;
    }

    public void setResponseStatusCode(Integer responseStatusCode) {
        this.responseStatusCode = responseStatusCode;
    }

    public String getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(String responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    public byte[] getResponsePayload() {
        return responsePayload;
    }

    public void setResponsePayload(byte[] responsePayload) {
        this.responsePayload = responsePayload;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

