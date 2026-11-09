package io.deltaflow.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IdempotencyRecordId implements Serializable {

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    public IdempotencyRecordId() {
    }

    public IdempotencyRecordId(String tenantId, String idempotencyKey) {
        this.tenantId = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyRecordId that = (IdempotencyRecordId) o;
        return Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, idempotencyKey);
    }
}

