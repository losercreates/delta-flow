# Phase 0: Research & Technical Decisions: Enterprise Security, Multi-Tenancy RBAC & Distributed Rate Limiting

## Decision 1: Dual-Mode Spring Security 6 Architecture (Stateless JWT + API-Key)

- **Decision**: Configure Spring Security 6 with a stateless `SecurityFilterChain` incorporating two specialized filters in sequence before `UsernamePasswordAuthenticationFilter`:
  1. `ApiKeyAuthenticationFilter`: Inspects the `X-API-Key` header for machine-to-machine financial clients.
  2. `JwtAuthenticationFilter`: Inspects the `Authorization: Bearer <token>` header for operator dashboard and web consumers.
- **Rationale**:
  - DeltaFlow serves two distinct consumers: automated financial services/webhooks initiating payment transfers via CLI/services, and human operators monitoring the Operator Dashboard.
  - Using a single stateless filter chain avoids HTTP sessions (`SessionCreationPolicy.STATELESS`), preserving zero-downtime horizontal scaling and strict microservice statelessness.
  - Public infrastructure probes (`/actuator/health`, `/actuator/prometheus`) and login endpoints (`/api/v1/auth/login`) are explicitly permitted without credentials.
- **Alternatives Considered**:
  - *Stateful HTTP Sessions with Cookies*: Rejected because sticky sessions hinder horizontal auto-scaling and violate cloud-native stateless principles.
  - *External OAuth2 Identity Provider (Keycloak/Okta) only*: Rejected for local integration testing simplicity; DeltaFlow implements standard JWT token generation/validation with HMAC-SHA256 that can be easily plugged into external OIDC providers.

---

## Decision 2: Distributed Multi-Tenant Rate Limiting (Redisson `RRateLimiter` vs. Custom Lua)

- **Decision**: Implement distributed token-bucket rate limiting using Redisson's native `RRateLimiter` partitioned by `tenantId` (`deltaflow:ratelimit:tenant:{tenantId}`).
- **Rationale**:
  - Redisson is already part of DeltaFlow's core infrastructure (v3.37.0) used for distributed locks and leader election.
  - Redisson's `RRateLimiter` implements an atomic sliding-window token bucket in Redis via server-side Lua scripts, handling replenish intervals and burst capacity without race conditions.
  - By keying the rate limiter to `tenantId`, each tenant has an isolated bucket (e.g. 100 req/sec, burst 200). Saturated tenants return HTTP `429 Too Many Requests` without consuming downstream PostgreSQL connections.
  - Standard RFC rate limit headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`) are returned on every rate-limited response.
- **Alternatives Considered**:
  - *In-Memory Bucket4j*: Rejected because independent worker instances would maintain isolated local counters, failing to enforce aggregate cluster-wide tenant rate limits.
  - *Database-backed counter table*: Rejected because database row updates for every API call create severe lock contention on PostgreSQL.

---

## Decision 3: Constant-Time API-Key Storage & Verification

- **Decision**: Store pre-shared API keys as SHA-256 cryptographic digests in a new table `tenant_api_keys` (`V7__tenant_api_keys.sql`), and verify client-submitted keys using constant-time byte array comparison (`MessageDigest.isEqual`).
- **Rationale**:
  - Plaintext API key storage in databases is a critical security vulnerability.
  - Standard string equality checks (`String.equals()`) terminate early upon the first mismatched character, exposing timing side-channel vulnerabilities that allow attackers to deduce valid keys byte by byte.
  - `MessageDigest.isEqual()` ensures identical execution time regardless of mismatch position.
- **Alternatives Considered**:
  - *BCrypt for API keys*: Rejected because BCrypt is intentionally slow (100-300ms compute cost), which would introduce unacceptable latency overhead on high-throughput financial transaction ingress. SHA-256 with high-entropy keys (`df_live_...` with 256 bits of entropy) provides sub-millisecond verification.

---

## Decision 4: Fine-Grained Role Hierarchy & Method-Level Security

- **Decision**: Define four explicit security roles:
  - `ROLE_ADMIN`: Superuser authority. Required for chaos injection, circuit breaker override, DLQ replay, retention purges, and cluster manipulation.
  - `ROLE_OPERATOR`: Support authority. Allowed to trigger saga compensations, retry steps, inspect forensics, and view DLQ poison pills.
  - `ROLE_VIEWER`: Read-only authority. Allowed to view the DAG visualizer, stream dashboard telemetry, and inspect transactions.
  - `ROLE_SERVICE`: Payment client authority. Scoped strictly to `POST /api/v1/transfers` for its provisioned tenant.
- **Rationale**:
  - Complies with Constitution Principle V (Immutable Audit Trail) and SOC2/PCI-DSS separation of duties.
  - Method-level `@PreAuthorize("hasRole('ADMIN')")` provides defense-in-depth on top of HTTP URL pattern matchers in `SecurityFilterChain`.
  - Unauthorized access attempts emit a `SECURITY_ACCESS_DENIED` audit log capturing principal identity, tenant, client IP, and target resource.

---

## Decision 5: Operator Dashboard Session Management & Role-Aware UI

- **Decision**: Implement an `AuthContext` in React providing login state, current bearer token storage (in `sessionStorage`), and active role inspection (`hasRole('ADMIN')`).
- **Rationale**:
  - Allows operators to supply bearer credentials or log in with credentials.
  - Destructive UI buttons (Chaos latency injection, DLQ replay, on-demand retention purge) dynamically display a locked state or are hidden when the current session lacks `ROLE_ADMIN` or `ROLE_OPERATOR`.
  - Automatically attaches `Authorization: Bearer <token>` to all API requests via centralized fetch interceptors.
