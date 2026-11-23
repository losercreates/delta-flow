# Phase 1: Data Model & Schema Migrations: Enterprise Security, Multi-Tenancy RBAC & Distributed Rate Limiting

## 1. Database Schema Migration `V7__tenant_api_keys.sql`

```sql
-- Flyway Migration: V7__tenant_api_keys.sql
-- Table for storing pre-shared machine client API keys with cryptographic hash digests

CREATE TABLE IF NOT EXISTS tenant_api_keys (
    key_id VARCHAR(64) PRIMARY KEY,
    hashed_key VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    description VARCHAR(256),
    roles VARCHAR(256) NOT NULL DEFAULT 'ROLE_SERVICE',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Partial index for fast constant-time lookup of enabled keys
CREATE INDEX IF NOT EXISTS idx_tenant_api_keys_hash 
    ON tenant_api_keys (hashed_key) 
    WHERE enabled = TRUE;

-- Pre-seed default test machine keys for integration testing
-- Key: df_live_tenant_alpha_secret_key_12345 (SHA-256 hashed)
INSERT INTO tenant_api_keys (key_id, hashed_key, tenant_id, description, roles, enabled)
VALUES (
    'key-alpha-service',
    '70be0b26b3c2e17e4763297a73f554695029c0f0653d997cc0f287e076618451',
    'tenant-alpha',
    'Tenant Alpha Machine Service Account',
    'ROLE_SERVICE',
    TRUE
) ON CONFLICT (key_id) DO NOTHING;
```

---

## 2. Core Security Domain Structures

### 2.1 `SecurityPrincipal`
Represents the authenticated subject bound to the security context thread.
```java
package io.deltaflow.security.domain;

import java.util.Set;

public record SecurityPrincipal(
    String principalId,
    String tenantId,
    Set<String> roles,
    AuthenticationType authType
) {
    public boolean hasRole(String role) {
        return roles != null && (roles.contains(role) || roles.contains("ROLE_" + role));
    }
}
```

### 2.2 `AuthenticationType`
```java
package io.deltaflow.security.domain;

public enum AuthenticationType {
    JWT_BEARER,
    API_KEY,
    ANONYMOUS
}
```

### 2.3 Authentication DTOs

#### `AuthLoginRequest`
```java
package io.deltaflow.security.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthLoginRequest(
    @NotBlank String username,
    @NotBlank String password,
    String tenantId
) {}
```

#### `AuthLoginResponse`
```java
package io.deltaflow.security.dto;

import java.util.Set;

public record AuthLoginResponse(
    String token,
    String tokenType,
    long expiresInSeconds,
    String tenantId,
    Set<String> roles
) {}
```

#### `CurrentUserProfileDto`
```java
package io.deltaflow.security.dto;

import java.util.Set;

public record CurrentUserProfileDto(
    String principalId,
    String tenantId,
    Set<String> roles,
    String authType
) {}
```

#### `TenantRateLimitStatusDto`
```java
package io.deltaflow.security.dto;

public record TenantRateLimitStatusDto(
    String tenantId,
    long limit,
    long remaining,
    long resetMs,
    boolean rateLimited
) {}
```

---

## 3. Frontend TypeScript Interfaces (`frontend/src/types/security.ts`)

```typescript
export interface AuthLoginResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  tenantId: string;
  roles: string[];
}

export interface CurrentUserProfileDto {
  principalId: string;
  tenantId: string;
  roles: string[];
  authType: 'JWT_BEARER' | 'API_KEY' | 'ANONYMOUS';
}

export interface TenantRateLimitStatusDto {
  tenantId: string;
  limit: number;
  remaining: number;
  resetMs: number;
  rateLimited: boolean;
}
```

---

## 4. Rate Limiting Policy Configuration

```yaml
deltaflow:
  security:
    enabled: true
    jwt:
      secret: "deltaflow-super-secret-hmac-sha256-signing-key-for-financial-grade-tokens-2026"
      expiration-seconds: 86400
      clock-skew-seconds: 60
    rate-limit:
      enabled: true
      default-rate: 100       # 100 requests per second
      default-burst: 200      # 200 burst capacity
      tenants:
        tenant-tier-low:
          rate: 10
          burst: 20
        tenant-tier-high:
          rate: 500
          burst: 1000
```
