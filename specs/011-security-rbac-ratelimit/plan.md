# Implementation Plan: Enterprise Security, Multi-Tenancy RBAC & Distributed Rate Limiting

**Branch**: `011-security-rbac-ratelimit` | **Date**: 2026-11-23 | **Spec**: [spec.md](file:///home/mayukh/projects/delta-flow/specs/011-security-rbac-ratelimit/spec.md)

**Input**: Feature specification from `/specs/011-security-rbac-ratelimit/spec.md`

## Summary

Harden DeltaFlow with enterprise-grade authentication, role-based access control (RBAC), and multi-tenant traffic shaping. Implement Spring Security 6 with dual-mode authentication: stateless HMAC/RSA JWT bearer tokens for human operators and constant-time hashed API keys (`X-API-Key`) for machine-to-machine financial clients. Establish role hierarchies (`ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_VIEWER`, `ROLE_SERVICE`) protecting sensitive Operator Dashboard, Chaos Injection, DLQ Replay, and Cluster endpoints. Implement a distributed Redis-backed token-bucket rate limiter per `tenantId` with configurable burst thresholds, returning standard HTTP 429 Too Many Requests with `X-RateLimit` headers and audit event emission. Update the React Operator Dashboard with session management and dynamic role-based UI access control, verified through comprehensive Testcontainers security and rate-limiting integration tests.

---

## Technical Context

**Language/Version**: Java 21 LTS (Virtual Threads enabled), TypeScript 5.5 (React 18 frontend)  
**Primary Dependencies**:
- Backend: `spring-boot-starter-security`, `io.jsonwebtoken:jjwt-api:0.12.6`, `jjwt-impl:0.12.6`, `jjwt-jackson:0.12.6`, `redisson-spring-boot-starter:3.37.0`, `spring-security-test`
- Frontend: `lucide-react`, React 18, Vite  
**Storage**:
- PostgreSQL 16: `tenant_api_keys` table for constant-time hashed API keys (Flyway migration `V7__tenant_api_keys.sql`).
- Redis 7: Redisson `RRateLimiter` per tenant (`deltaflow:ratelimit:tenant:{tenantId}`).  
**Testing**: JUnit 5, AssertJ, Spring Boot Test, Testcontainers (PostgreSQL, Redis, Kafka).  
**Target Platform**: Linux / Containerized Cloud Environments, Docker.  
**Project Type**: High-Throughput Distributed Financial Orchestration Platform & Operator Web Console.  
**Performance Goals**: Sub-5ms authentication validation latency; $<2$ms rejection latency for throttled (HTTP 429) requests.  
**Constraints**: Zero double-spend guarantees preserved (Constitution Principle I); constant-time API-key verification preventing timing side-channel attacks; zero cross-tenant quota bleed under burst saturation.

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principle I (Zero Double-Spend & Financial Invariance)**:
  - Rate limiting and security filters execute *before* transaction initiation, consuming 0 database connections when requests are rejected.
  - Active `PROCESSING` and `RESOLVED` idempotency records remain strictly isolated per `tenantId`.
  - **Verdict**: PASS.
- **Principle II (Strict Backward Compensation & Zombie-Proofing)**:
  - Fencing tokens and compensation triggers are protected under `ROLE_ADMIN` and `ROLE_OPERATOR`. Unauthorized compensation attempts are blocked.
  - **Verdict**: PASS.
- **Principle III (Compile-Time Exhaustion & Type Safety)**:
  - Authentication types (`JWT_BEARER`, `API_KEY`, `ANONYMOUS`) and SecurityPrincipals are modeled with Java 21 sealed interfaces/records.
  - **Verdict**: PASS.
- **Principle IV (Test-Driven & Chaos-Verified)**:
  - Mandatory integration tests using real Testcontainers (PostgreSQL, Redis) validating 401, 403, 429 response codes, role separation, and rate limiting isolation.
  - **Verdict**: PASS.
- **Principle V (Observability & Immutable Audit Trail)**:
  - All security events (`SECURITY_ACCESS_DENIED`, `TENANT_RATE_LIMITED`, `OPERATOR_LOGIN`) are written to the append-only immutable audit trail with principal ID, tenant ID, and client IP.
  - **Verdict**: PASS.

---

## Project Structure

### Documentation (`specs/011-security-rbac-ratelimit/`)
```text
specs/011-security-rbac-ratelimit/
├── spec.md                     # Feature specification
├── plan.md                     # This plan
├── research.md                 # Technical decisions (JWT, Redisson RRateLimiter, constant-time hashing)
├── data-model.md               # DB migration V7, SecurityPrincipal, and DTO definitions
├── quickstart.md               # 4 concrete verification scenarios
├── contracts/
│   └── security-rbac-api.yaml  # OpenAPI 3.0 specification for auth and rate-limiting
└── checklists/
    └── requirements.md         # 16/16 requirements validation passed
```

### Source Code (`src/` and `frontend/`)
```text
src/main/java/io/deltaflow/
├── security/
│   ├── config/
│   │   ├── SecurityConfig.java                 # SecurityFilterChain, CORS, stateless session
│   │   └── RateLimitConfig.java                # TenantRateLimitPolicy properties
│   ├── domain/
│   │   ├── SecurityPrincipal.java              # Authenticated principal record
│   │   ├── AuthenticationType.java             # Enum: JWT_BEARER, API_KEY, ANONYMOUS
│   │   └── TenantApiKey.java                   # JPA Entity for tenant_api_keys
│   ├── repository/
│   │   └── TenantApiKeyRepository.java         # Lookup by hashed_key
│   ├── filter/
│   │   ├── JwtAuthenticationFilter.java        # Authorization: Bearer <jwt>
│   │   ├── ApiKeyAuthenticationFilter.java     # X-API-Key: df_live_...
│   │   └── TenantRateLimitingFilter.java       # Distributed Redisson token-bucket limiter
│   ├── service/
│   │   ├── JwtTokenService.java                # Token generation, parsing, validation
│   │   ├── ApiKeyAuthenticationService.java    # Constant-time hashing & validation
│   │   └── TenantRateLimiterService.java       # Redisson RRateLimiter integration
│   ├── api/
│   │   └── AuthApiController.java              # /api/v1/auth/login, /me, /rate-limit/status
│   └── dto/
│       ├── AuthLoginRequest.java
│       ├── AuthLoginResponse.java
│       ├── CurrentUserProfileDto.java
│       └── TenantRateLimitStatusDto.java
└── common/
    └── audit/                                  # SECURITY_ACCESS_DENIED, TENANT_RATE_LIMITED events

src/main/resources/
└── db/migration/
    └── V7__tenant_api_keys.sql                 # API keys table and partial index

frontend/src/
├── types/
│   └── security.ts                             # Auth and rate limit interfaces
├── services/
│   └── authApi.ts                              # Login, me, rate-limit status endpoints
├── context/
│   └── AuthContext.tsx                         # Operator session, token injection, hasRole()
└── components/
    ├── auth/
    │   └── OperatorLoginModal.tsx              # Login dialog & active role switcher
    └── telemetry/
        └── TelemetryHeader.tsx                 # Role badge & tenant rate limit indicator
```

---

## Phase 0: Research Decisions Summary

All 5 core architectural decisions are resolved in [`research.md`](file:///home/mayukh/projects/delta-flow/specs/011-security-rbac-ratelimit/research.md):
1. **Dual-Mode Filter Chain**: `ApiKeyAuthenticationFilter` + `JwtAuthenticationFilter` placed before standard Spring Security filters on a `STATELESS` session policy.
2. **Redisson `RRateLimiter`**: Sliding token bucket per `tenantId` in Redis with atomic refill and RFC headers (`X-RateLimit-*`).
3. **Constant-Time Verification**: SHA-256 digest lookup in `tenant_api_keys` with `MessageDigest.isEqual` to defeat timing side-channel attacks.
4. **Role Hierarchy**: `ROLE_ADMIN` > `ROLE_OPERATOR` > `ROLE_VIEWER`, plus `ROLE_SERVICE` for API ingress.
5. **Dashboard Auth Context**: Centralized token management and dynamic button disabling with lock tooltips.

---

## Phase 1: Design Artifacts Summary

- **Database Migration**: [`V7__tenant_api_keys.sql`](file:///home/mayukh/projects/delta-flow/specs/011-security-rbac-ratelimit/data-model.md#1-database-schema-migration-v7__tenant_api_keyssql)
- **Data Models & DTOs**: [`data-model.md`](file:///home/mayukh/projects/delta-flow/specs/011-security-rbac-ratelimit/data-model.md)
- **API Contracts**: [`contracts/security-rbac-api.yaml`](file:///home/mayukh/projects/delta-flow/specs/011-security-rbac-ratelimit/contracts/security-rbac-api.yaml)
- **Verification Scenarios**: [`quickstart.md`](file:///home/mayukh/projects/delta-flow/specs/011-security-rbac-ratelimit/quickstart.md)
