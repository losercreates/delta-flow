package io.deltaflow.security.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tenant_api_keys", indexes = {
    @Index(name = "idx_tenant_api_keys_hash", columnList = "hashed_key")
})
public class TenantApiKey {

    @Id
    @Column(name = "key_id", length = 64, nullable = false)
    private String keyId;

    @Column(name = "hashed_key", length = 64, nullable = false)
    private String hashedKey;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "roles", length = 256, nullable = false)
    private String roles;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TenantApiKey() {
    }

    public TenantApiKey(String keyId, String hashedKey, String tenantId, String description, String roles) {
        this.keyId = keyId;
        this.hashedKey = hashedKey;
        this.tenantId = tenantId;
        this.description = description;
        this.roles = roles != null ? roles : "ROLE_SERVICE";
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (roles == null) {
            roles = "ROLE_SERVICE";
        }
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getHashedKey() {
        return hashedKey;
    }

    public void setHashedKey(String hashedKey) {
        this.hashedKey = hashedKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
