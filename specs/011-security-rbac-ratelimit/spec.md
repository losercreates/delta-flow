# Feature Specification: Enterprise Security, Multi-Tenancy RBAC & Distributed Rate Limiting

**Feature Branch**: `011-security-rbac-ratelimit`  
**Created**: 2026-11-23  
**Status**: Draft  
**Input**: User description: "Enterprise Security, Multi-Tenancy RBAC & Distributed Rate Limiting: Harden DeltaFlow with enterprise-grade authentication, role-based access control (RBAC), and multi-tenant traffic shaping. Implement Spring Security 6 with dual-mode authentication: stateless HMAC/RSA JWT bearer tokens for human operators and constant-time hashed API keys (X-API-Key) for machine-to-machine financial clients. Establish role hierarchies (ROLE_ADMIN, ROLE_OPERATOR, ROLE_VIEWER, ROLE_SERVICE) protecting sensitive Operator Dashboard, Chaos Injection, DLQ Replay, and Cluster endpoints. Implement a distributed Redis-backed token-bucket rate limiter per tenantId with configurable burst thresholds, returning standard HTTP 429 Too Many Requests with X-RateLimit headers and audit event emission. Update the React Operator Dashboard with session management and dynamic role-based UI access control, verified through comprehensive Testcontainers security and rate-limiting integration tests."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Stateless Authentication & Dual-Mode Credential Verification (Priority: P1) 🎯 MVP

As an API client or operations engineer, I need to present verifiable credentials (either a signed operator session token or a client API key) so that DeltaFlow can authenticate my identity, establish my tenant context, and reject unauthenticated requests before executing business logic.

**Why this priority**: Core security foundation. Without strict authentication, multi-tenant boundaries and audit attribution cannot be trusted, violating Constitution Principle I (Zero Double-Spend) and Principle V (Immutable Audit Trail).

**Independent Test**: Send requests with no token, an expired token, a tampered token, a valid bearer token, and a valid `X-API-Key`. Verify that invalid requests receive immediate HTTP 401 Unauthorized, while valid credentials succeed and resolve tenant identity.

**Acceptance Scenarios**:
1. **Given** a request bearing a valid signed bearer token with user identity and tenant context, **When** submitting to protected endpoints, **Then** the request is authenticated, the tenant context is bound to the execution thread, and processing continues.
2. **Given** a machine-to-machine request bearing a valid `X-API-Key`, **When** submitting to transaction ingress endpoints, **Then** the API key is verified via constant-time cryptographic hash comparison, mapped to its configured tenant, and allowed to proceed.
3. **Given** a request with a missing, expired, or cryptographically altered credential, **When** attempting to access any protected endpoint, **Then** the system immediately rejects the request with HTTP 401 Unauthorized and standard challenge headers, without invoking controller handlers.
4. **Given** an unauthenticated request to public infrastructure probes (e.g., `/actuator/health`), **When** queried by container orchestrators, **Then** the request is allowed without authentication challenges.

---

### User Story 2 - Role-Based Access Control (RBAC) & Privilege Separation (Priority: P1) 🎯 MVP

As a security auditor or compliance officer, I need fine-grained role-based permissions across system operations so that operators can inspect forensics and monitor transactions without unauthorized access to destructive chaos injection, data retention purges, or cluster leader overrides.

**Why this priority**: Enforces principle of least privilege. In enterprise financial platforms, separation of duties between read-only viewers, support operators, service accounts, and administrators is mandatory for compliance (PCI-DSS / SOC-2).

**Independent Test**: Authenticate as `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_SERVICE`, and `ROLE_ADMIN`. Attempt operational actions across each role tier. Verify that `ROLE_VIEWER` cannot trigger saga interventions or chaos, `ROLE_OPERATOR` can intervene but cannot inject chaos or delete data, and only `ROLE_ADMIN` can execute destructive operations.

**Role Hierarchy**:
- **`ROLE_ADMIN`**: Full administrative capabilities (chaos injection/healing, manual DLQ replaying, on-demand retention pruning, cluster leader manipulation).
- **`ROLE_OPERATOR`**: Operational oversight and safe remediation (saga step retry, manual compensation triggers, DLQ inspection, forensic lookup).
- **`ROLE_VIEWER`**: Read-only observation (DAG visualizer, dashboard telemetry, transaction status, audit inspection).
- **`ROLE_SERVICE`**: Financial API ingress (`POST /api/v1/transfers`), strictly bound to its provisioned tenant.

**Acceptance Scenarios**:
1. **Given** an authenticated principal with `ROLE_ADMIN`, **When** invoking chaos injection (`POST /api/v1/chaos/inject`) or triggering retention pruning, **Then** the request executes successfully and records an audit log with the administrator identity.
2. **Given** an authenticated principal with `ROLE_VIEWER`, **When** attempting to intervene in a saga (`POST /api/v1/dashboard/sagas/{id}/intervene`), **Then** the request is denied with HTTP 403 Forbidden and the saga state remains unchanged.
3. **Given** an authenticated client with `ROLE_SERVICE`, **When** attempting to access operator cluster nodes or chaos control endpoints, **Then** access is denied with HTTP 403 Forbidden.
4. **Given** any unauthorized access attempt, **When** rejected with HTTP 403, **Then** a `SECURITY_ACCESS_DENIED` security audit event is written with principal, attempted endpoint, tenant, and client IP.

---

### User Story 3 - Multi-Tenant Distributed Token-Bucket Rate Limiting (Priority: P2)

As a financial gateway platform engineer, I need distributed rate limiting partitioned by tenant so that high-volume tenants or buggy client applications cannot starve cluster resources or degrade performance for other co-hosted tenants.

**Why this priority**: Prevents noisy-neighbor resource starvation, ensures high availability under traffic spikes, and enforces fair resource sharing in multi-tenant environments.

**Independent Test**: Configure a tenant with a sustained rate limit (e.g., 20 req/s, burst 50). Flood requests from that tenant until quota exhausts; verify HTTP 429 Too Many Requests with rate limit headers. Concurrently submit requests from a secondary tenant; verify that the secondary tenant receives HTTP 200/201 with zero throughput degradation.

**Acceptance Scenarios**:
1. **Given** a tenant with an allotted rate limit policy, **When** incoming requests arrive within quota, **Then** requests proceed normally and response headers include `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset`.
2. **Given** a tenant exceeding their burst token bucket, **When** submitting subsequent requests, **Then** the gateway immediately returns HTTP 429 Too Many Requests with a `Retry-After` header and emits a `TENANT_RATE_LIMITED` audit event without invoking database transactions.
3. **Given** Tenant A is throttled at HTTP 429, **When** Tenant B submits requests within their quota, **Then** Tenant B's requests succeed with zero latency penalty or quota leakage from Tenant A.
4. **Given** a burst of requests across multiple clustered application nodes, **When** consuming the tenant's token bucket, **Then** token replenishment and consumption remain synchronized across all nodes via distributed coordination.

---

### User Story 4 - Operator Dashboard Session Handling & Dynamic Role-Based UI Controls (Priority: P2)

As an operations engineer using the web console, I need the dashboard to display my authenticated user session, show my granted roles, and dynamically adjust UI controls so that unauthorized actions (like chaos injection or DLQ replay) are disabled or hidden based on my permissions.

**Why this priority**: Prevents operator error and enhances usability by aligning visual capabilities directly with backend authorization rules.

**Independent Test**: Log into the Operator Dashboard as a Viewer user; verify that the Chaos Injection tab and DLQ Replay buttons are disabled or display a lock tooltip. Switch sessions to an Admin user; verify that all buttons and operational panels become active.

**Acceptance Scenarios**:
1. **Given** an operator viewing the dashboard as `ROLE_VIEWER`, **When** inspecting sagas, **Then** intervention buttons, DLQ replay controls, and chaos injection forms are disabled with tooltips indicating required permissions.
2. **Given** an operator viewing the dashboard as `ROLE_ADMIN`, **When** viewing the interface, **Then** all operational, chaos injection, and retention controls are fully enabled with confirmation modals.
3. **Given** a session where the authentication token expires, **When** the operator attempts an action, **Then** the dashboard displays an authentication dialog prompting for re-login and prevents sending unauthenticated requests.
4. **Given** active rate limiting on a tenant, **When** viewing the dashboard, **Then** telemetry badges display current tenant rate limit consumption and remaining token quota.

---

### Edge Cases

- **Clock Skew on Token Expiry**: Token validation MUST permit a configurable clock skew window (default: 60 seconds) to prevent false-positive expirations in distributed environments.
- **Constant-Time Key Comparison**: Validation of API keys MUST use constant-time byte array comparison (`MessageDigest.isEqual`) to prevent timing side-channel attacks against secret keys.
- **Missing or Ambiguous Tenant Context**: If an authenticated token specifies a tenant that does not match the requested resource's tenant, the request MUST be rejected with HTTP 403 Forbidden.
- **Distributed Cache Isolation during Outage**: If the distributed rate limiter store experiences transient connectivity loss, rate limiting MUST fail-open with rate limiting bypass warnings rather than dropping valid financial payments.
- **Token Bucket Race Conditions**: Token decrement and replenishment MUST execute atomically to prevent burst over-allocation during concurrent request arrival.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST authenticate all requests to `/api/v1/**` (excluding public endpoints `/actuator/health` and `/api/v1/auth/login`) using either a signed bearer token or a pre-shared API key.
- **FR-002**: System MUST support stateless bearer tokens containing claims for subject (`sub`), tenant identifier (`tenantId`), and granted roles list (`roles`).
- **FR-003**: System MUST support client authentication via HTTP header `X-API-Key`, verifying key digests using constant-time cryptographic hash comparison.
- **FR-004**: System MUST establish a security context binding authenticated principal, identity, and tenant ID to the active thread for every request.
- **FR-005**: System MUST enforce role-based access control with standard roles: `ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_VIEWER`, and `ROLE_SERVICE`.
- **FR-006**: System MUST restrict endpoints:
  - Chaos Control (`/api/v1/chaos/**`): Requires `ROLE_ADMIN`.
  - Cluster Lease/Retention Triggers (`/api/v1/cluster/retention/trigger`, `/api/v1/cluster/outbox/reclaim-leases`): Requires `ROLE_ADMIN`.
  - Operator Intervention (`/api/v1/dashboard/sagas/{id}/intervene`, `/api/v1/dashboard/dlq/replay`): Requires `ROLE_ADMIN` or `ROLE_OPERATOR`.
  - Dashboard & Telemetry Inspection (`/api/v1/dashboard/**`, `/api/v1/cluster/**`): Requires `ROLE_ADMIN`, `ROLE_OPERATOR`, or `ROLE_VIEWER`.
  - Payment Transaction Ingress (`POST /api/v1/transfers`): Requires `ROLE_SERVICE` or `ROLE_ADMIN`.
- **FR-007**: System MUST reject unauthorized access attempts with HTTP 403 Forbidden and record a `SECURITY_ACCESS_DENIED` event in the immutable audit trail.
- **FR-008**: System MUST implement distributed token-bucket rate limiting per `tenantId` with configurable rates (sustained requests/sec) and burst capacity.
- **FR-009**: When a tenant exceeds rate limits, the system MUST return HTTP 429 Too Many Requests with standard response headers:
  - `X-RateLimit-Limit`: Maximum quota allowed in current window.
  - `X-RateLimit-Remaining`: Number of tokens remaining in the bucket.
  - `X-RateLimit-Reset`: Milliseconds until the token bucket is replenished.
  - `Retry-After`: Recommended backoff in seconds.
- **FR-010**: System MUST emit a `TENANT_RATE_LIMITED` audit event when rate limiting rejects a transaction, recording tenant ID, client IP, and attempted endpoint.
- **FR-011**: System MUST expose an endpoint `GET /api/v1/auth/me` returning the current authenticated principal profile, tenant, and granted authorities.
- **FR-012**: Operator Dashboard MUST provide an authentication status indicator and token dialog allowing operators to log in and inspect their active roles.
- **FR-013**: Operator Dashboard MUST dynamically adjust UI controls, disabling or hiding destructive actions for users lacking `ROLE_ADMIN` or `ROLE_OPERATOR`.

---

### Key Entities

- **SecurityPrincipal**: Represents the authenticated caller, containing `principalId`, `tenantId`, `roles` (`Set<String>`), and authentication type (`TOKEN` or `API_KEY`).
- **ApiKeyCredential**: Entity representing provisioned machine client credentials, storing `keyId`, `hashedKey`, `tenantId`, `description`, `enabled`, and `createdAt`.
- **TenantRateLimitPolicy**: Configuration mapping a `tenantId` to permitted `sustainedRate` (tokens/sec) and `burstCapacity` (max tokens).

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of unauthenticated requests to protected endpoints are rejected with HTTP 401 Unauthorized in $<5$ms without executing downstream handlers.
- **SC-002**: 100% of unauthorized role access attempts are denied with HTTP 403 Forbidden with zero mutation to underlying data stores.
- **SC-003**: Rate-limited transactions receive HTTP 429 in $<2$ms, consuming zero database connection pool capacity.
- **SC-004**: Multi-tenant isolation verified with zero throughput degradation on independent tenants during 10x burst traffic surges on a saturated tenant.
- **SC-005**: All security access denials and rate limit events are recorded in the immutable audit log with tenant ID, principal, and timestamp.
- **SC-006**: Existing regression integration tests continue to pass 100% with security configured.

---

## Assumptions

- Pre-shared API keys and default tenant profiles can be configured via application properties or environment variables for integration testing and local development.
- Tokens use standard HMAC-SHA256 signatures with a configurable secret key for development, with architecture compatible with external OAuth2/OIDC identity providers.
- Redis remains the central coordination store for distributed token-bucket rate limiting, matching existing Redisson distributed lock architecture.
