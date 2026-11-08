# Feature Specification: Milestone 1: Foundations & Distributed Idempotency Gate

**Feature Branch**: `001-idempotency-gate`

**Created**: 2026-11-08

**Status**: Draft

**Input**: User description: "Milestone 1: Foundations & Distributed Idempotency Gate
Build the high-consistency idempotency proxy layer for DeltaFlow in Java 21 / Spring Boot 3 with Redis and PostgreSQL fallback.
- Intercept HTTP requests with Idempotency-Key header.
- Compute and verify SHA-256 request payload digest against existing records to detect body tampering.
- Dual-stage distributed locking (Pending -> Processing -> Resolved / Failed) with lease timeouts.
- Cache and return original terminal HTTP responses on duplicate keys without re-executing."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Exact Replay Deduplication with Cached Response (Priority: P1) 🎯 MVP

As an upstream client (payment gateway, merchant checkout, or internal microservice), when I send a financial transaction request with an `Idempotency-Key`, and that key has already been resolved in a prior request with the exact same payload, I want to receive the cached terminal HTTP response immediately without re-executing any downstream processing, so that double debits or duplicate payment actions are impossible.

**Why this priority**: This is the core guarantee of the idempotency gate and the foundation of financial safety (Principle I in Constitution). Without it, duplicate client retries cause financial leakage.

**Independent Test**:
Submit a POST request with `Idempotency-Key: DF-TEST-001` and payload `{"amount": 500, "currency": "USD"}`. Downstream mock processes it and returns HTTP 201 Created. Immediately resend the exact same HTTP request. Verify downstream is NOT called a second time, and the response is HTTP 201 with identical response body and a header `Idempotency-Status: CACHED`.

**Acceptance Scenarios**:

1. **Given** an existing `Resolved` idempotency record for `(tenant_id, "DF-TEST-001")`, **When** an incoming request arrives with `Idempotency-Key: DF-TEST-001` and matching SHA-256 payload digest, **Then** return HTTP 201 with the cached headers and response body, emit header `Idempotency-Status: CACHED`, and do not call downstream handlers.
2. **Given** no prior record for `(tenant_id, "DF-NEW-002")`, **When** a valid request arrives, **Then** create a record in `Pending` state, transition to `Processing`, execute downstream handler, store the terminal response (HTTP status, headers, body) in `Resolved` state, and return the response to the caller.

---

### User Story 2 - Payload Tampering & Digest Mismatch Protection (Priority: P2)

As a FinTech platform security engineer, when a client reuses an existing `Idempotency-Key` but alters the payload (e.g. changing the transaction amount, currency, or recipient), I want the system to detect the tampering via SHA-256 digest comparison and immediately reject the request with HTTP 409 Conflict, so that clients cannot hijack or corrupt previous transaction records.

**Why this priority**: Essential security protection against replay attacks, client-side bugs, or key collision vulnerabilities.

**Independent Test**:
Complete a transaction with `Idempotency-Key: DF-PAY-003` for amount $100. Then send a second request with the same `Idempotency-Key: DF-PAY-003` but payload modified to $500. Verify the system rejects it immediately with HTTP 409 Conflict and error code `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH` without calling any downstream services.

**Acceptance Scenarios**:

1. **Given** an existing idempotency record with key `DF-PAY-003` and digest `H1`, **When** an incoming request arrives with `Idempotency-Key: DF-PAY-003` but its computed payload digest is `H2` where `H1 != H2`, **Then** return HTTP 409 Conflict with error code `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH` and descriptive message.
2. **Given** a request with an empty body and an `Idempotency-Key`, **When** submitted, **Then** compute the SHA-256 digest of the empty string (or canonicalized query params) and verify subsequent empty-body requests match consistently.

---

### User Story 3 - Concurrent Request Locking & In-Flight Collision Handling (Priority: P3)

As a distributed payment system, when two or more identical requests with the same `Idempotency-Key` arrive concurrently (within milliseconds of each other), I want DeltaFlow to acquire a distributed lock so that only one request enters the `Processing` state, while concurrent contenders wait or receive a safe in-flight notification rather than executing parallel duplicate transactions.

**Why this priority**: High-volume parallel retries from web clients or automated retry storms can hit the gateway simultaneously before the first request finishes.

**Independent Test**:
Fire 20 concurrent HTTP requests with the identical `Idempotency-Key` and payload simultaneously. Verify exactly 1 request executes the business logic, while the others either await resolution and receive the cached response or receive HTTP 409 / 425 with an in-flight conflict code.

**Acceptance Scenarios**:

1. **Given** an incoming request acquiring an active lock in state `Processing`, **When** a concurrent request arrives with the same key, **Then** the concurrent request MUST NOT initiate downstream processing.
2. **Given** a concurrent request polling for an in-flight key with a configurable wait timeout (e.g. up to 5 seconds), **When** the active request completes and transitions to `Resolved`, **Then** the waiting request immediately unblocks and returns the cached terminal response.
3. **Given** a concurrent contender whose wait timeout expires before the active request resolves, **Then** return HTTP 409 Conflict with error code `IDEMPOTENCY_KEY_IN_FLIGHT`.

---

### User Story 4 - Lease Timeout Expiration & Crash Recovery (Priority: P4)

As a systems operator, when an application instance crashes or network partitions while a request is in `Processing` state, I want the distributed lock lease to expire automatically after a configurable TTL (e.g. 30 seconds), so that subsequent retries can reclaim the lock or mark the stuck transaction as failed, avoiding permanent deadlock.

**Why this priority**: Critical resilience mechanism to prevent orphaned locks when workers crash mid-execution.

**Independent Test**:
Insert an idempotency record in `Processing` with a simulated lease timestamp expired 10 seconds ago. Send a new request with that same key. Verify the system detects the expired lease, reclaims the lock, updates the audit log with `LEASE_EXPIRED_RECLAIMED`, and executes safely.

**Acceptance Scenarios**:

1. **Given** an idempotency record in `Processing` whose `lease_expires_at` is in the past, **When** a retry arrives with the same key and valid payload digest, **Then** allow lock reclamation, update `lease_expires_at`, and process the request.
2. **Given** a request whose processing exceeds the lease TTL, **When** background cleaner or contender detects expired lease, **Then** log a warning and record a lease violation event in the audit trail.

---

### Edge Cases

- **Missing Idempotency-Key**: If an endpoint is configured as strictly idempotent and the header is missing, return HTTP 400 Bad Request (`MISSING_IDEMPOTENCY_KEY`). For optional endpoints, pass through without caching.
- **Large Request Payloads**: Requests exceeding a maximum buffer threshold (e.g. 2MB) must be rejected with HTTP 413 Payload Too Large before allocating hash buffer memory.
- **Downstream Failure Responses (4xx / 5xx)**: If downstream returns a 4xx client error (e.g., 404 Account Not Found), the negative terminal response must be cached in `Failed` state so retries do not re-trigger validation errors. For transient 5xx server errors, the lease is released or marked `Failed` permitting retry.
- **Redis Outage / Failover**: If Redis is unavailable or times out, the system must transparently fall back to PostgreSQL row-level pessimistic locking (`PESSIMISTIC_WRITE`) without dropping idempotency guarantees.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST intercept incoming HTTP POST, PUT, and PATCH requests containing the `Idempotency-Key` header prior to business execution.
- **FR-002**: The system MUST compute a cryptographic SHA-256 hash digest of the raw request payload before executing any downstream business logic.
- **FR-003**: The system MUST enforce a 4-state dual-stage lifecycle: `Pending`, `Processing`, `Resolved`, and `Failed`.
- **FR-004**: The system MUST verify incoming SHA-256 payload digests against existing records; if a key exists with a different digest, the system MUST reject the request with HTTP 409 Conflict and code `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`.
- **FR-005**: The system MUST return the cached HTTP status code, headers, and payload for any request matching a `Resolved` record without invoking downstream handlers.
- **FR-006**: The system MUST enforce a configurable lease timeout (default: 30 seconds) on `Pending` and `Processing` records.
- **FR-007**: The system MUST support distributed locking via a low-latency distributed cache with automatic fallback to relational database row-level locking.
- **FR-008**: The system MUST isolate records by tenant using a composite key: `(tenant_id, idempotency_key)`.
- **FR-009**: The system MUST buffer HTTP response status, headers, and body during first-time execution to persist into the idempotency store upon completion.
- **FR-010**: The system MUST emit structured audit log events with correlation IDs for all idempotency events (LOCK_ACQUIRED, CACHE_HIT, PAYLOAD_MISMATCH, LEASE_EXPIRED).

### Key Entities *(include if feature involves data)*

- **IdempotencyRecord**:
  - `tenantId` (String): Tenant identifier for multi-tenant isolation.
  - `idempotencyKey` (String): Unique key provided in the `Idempotency-Key` header.
  - `requestHash` (String): Hex-encoded SHA-256 hash of the normalized request body.
  - `status` (Enum): `PENDING`, `PROCESSING`, `RESOLVED`, `FAILED`.
  - `responseStatus` (Integer): Cached HTTP status code (e.g., 200, 201).
  - `responseHeaders` (Map/JSON): Serialized HTTP response headers.
  - `responseBody` (String/JSON): Serialized HTTP response body.
  - `leaseExpiresAt` (Instant): Expiration timestamp for the current lock lease.
  - `createdAt` (Instant): Creation timestamp.
  - `updatedAt` (Instant): Last update timestamp.

- **DistributedLock**:
  - `lockKey` (String): Formatted lock key (e.g., `lock:idempotency:{tenant}:{key}`).
  - `lockToken` (String): UUID identifying the current lock owner.
  - `ttl` (Duration): Remaining lease time.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of duplicate requests with matching idempotency keys return cached responses within 20 milliseconds without calling downstream services.
- **SC-002**: 100% of payload tampering attempts (same key with altered payload) are rejected with HTTP 409 Conflict.
- **SC-003**: Under 50 concurrent requests with the identical idempotency key, exactly 1 request executes downstream logic, and 0 duplicate executions occur.
- **SC-004**: During primary cache lock outage, the system falls back to database-level locking with zero double-spend or duplicate executions across 500 test transactions.
- **SC-005**: 100% of stuck `Processing` records whose lease expires can be reclaimed or safely failed by subsequent client retries after TTL expiration.

## Assumptions

- Target upstream clients provide an `Idempotency-Key` header with high entropy (e.g. UUID v4 or ULID).
- Request payloads for idempotent endpoints are JSON, text, or small binaries not exceeding 2MB.
- Clocks on all application instances and database servers are synchronized via NTP to within 100 milliseconds.
- Storage in PostgreSQL and Redis uses UTC timestamps exclusively.
