# Research & Technical Decisions: Milestone 1 - Foundations & Distributed Idempotency Gate

**Feature**: Milestone 1 - Foundations & Distributed Idempotency Gate  
**Branch**: `001-idempotency-gate`  
**Date**: 2026-11-08  
**Status**: Completed  

---

## 1. Distributed Locking Strategy & Store Hierarchy

### Decision
Implement a tiered locking mechanism with Redis as the primary low-latency distributed lock provider (via Redisson / atomic Lua scripts) and PostgreSQL row-level pessimistic locking (`PESSIMISTIC_WRITE` / `SELECT ... FOR UPDATE`) as an automatic fallback when Redis is unreachable.

### Rationale
- **Sub-millisecond latency**: Redis handles high-concurrency lock acquisition and release in under 1ms, preventing HTTP proxy bottlenecks during high-frequency traffic.
- **Fail-safe Durability**: Financial systems cannot tolerate dropped locks or single-point-of-failure split-brains. If Redis experiences network partition or node restart, the system falls back to PostgreSQL ACID transactions, preserving the zero double-spend guarantee (Constitution Principle I) at a minor cost to throughput.
- **Lease Expiration**: Redisson provides atomic lock leasing with auto-renewal ("watchdog") or explicit lease TTLs (default 30 seconds), ensuring crashed nodes never strand locked keys.

### Alternatives Considered
- **Pure Relational Database Locks (PostgreSQL only)**: Rejected as the sole provider due to database connection pool saturation under high-concurrency bursts (e.g., flash sales, retry storms).
- **Consul / Apache ZooKeeper**: Rejected due to operational complexity, heavy deployment footprints, and mismatched architectural goals for API proxy layers.

---

## 2. Request Body Hashing & Buffering Strategy

### Decision
Use Spring Boot's `ContentCachingRequestWrapper` within a high-precedence `OncePerRequestFilter` to buffer incoming request byte arrays. Compute an immutable SHA-256 digest in hex format over the normalized raw body bytes before dispatching the request to the controller or proxy chain.

### Rationale
- **Non-Destructive Stream Consumption**: Standard `HttpServletRequest.getInputStream()` can only be read once. Wrapping it in a cached wrapper allows DeltaFlow to inspect and hash the body while permitting downstream business logic to parse JSON or form parameters unimpeded.
- **Tampering Detection**: Verifying the SHA-256 hash guarantees that an existing `Idempotency-Key` cannot be replayed with altered financial payloads (amounts, accounts, currency), satisfying Constitution Principle I.
- **Empty / Non-Body Requests**: For requests with no body, compute the digest of the canonicalized query string or empty byte array to ensure consistent deterministic hashing.

### Alternatives Considered
- **Deserialized Object Hashing**: Rejected because JSON field reordering, floating-point serialization discrepancies, and whitespace differences could cause false-positive payload mismatch errors.
- **Streaming Hasher (Hashing on read)**: Rejected because duplicate detection and locking must occur *before* downstream execution begins, requiring upfront payload buffering up to a maximum size threshold (2MB).

---

## 3. Dual-Stage Idempotency Lifecycle & State Transitions

### Decision
Model the idempotency lifecycle with four explicit states: `PENDING` -> `PROCESSING` -> `RESOLVED` / `FAILED`.
- **PENDING**: Initial database record insertion indicating lock reservation.
- **PROCESSING**: Distributed lock successfully acquired; downstream execution is active.
- **RESOLVED**: Downstream execution completed with a terminal response (status code, headers, payload saved).
- **FAILED**: Downstream execution threw an unrecoverable exception or lease expired without resolution.

### Rationale
- **In-Flight Separation**: Distinguishes between completed transactions (which return cached data) and in-flight transactions (which require contenders to wait or reject).
- **Zombie Step Isolation**: Combined with fencing tokens and lease timeouts (Constitution Principle II), prevents delayed executions from overwriting failed or compensated records.

### Alternatives Considered
- **Binary State (Locked vs Unlocked)**: Rejected because it does not record whether a previous unlock was due to success, client error, or server crash, making safe automated retries impossible.

---

## 4. Concurrent Contender Strategy: Wait-and-Poll vs Immediate Conflict

### Decision
Implement a configurable contender strategy:
1. When a concurrent request hits an active `PROCESSING` key with a matching payload digest, it initiates a short polling loop (e.g., 100ms intervals using Redis Pub/Sub notification or lightweight Redis polling) up to a client wait budget (default: 3 seconds).
2. If the active transaction transitions to `RESOLVED` within the wait window, the contender immediately unblocks and returns the cached terminal response.
3. If the wait budget expires while still in `PROCESSING`, return HTTP 409 Conflict with code `IDEMPOTENCY_KEY_IN_FLIGHT`.

### Rationale
- **Optimal User Experience**: Upstream clients double-clicking "Pay" receive the completed transaction receipt rather than an error banner.
- **Gateway Protection**: Bounding the polling timeout to 3 seconds prevents thread starvation and connection pool exhaustion on upstream API gateways.

### Alternatives Considered
- **Immediate HTTP 409**: Rejected as the sole behavior because it pushes the retry logic and polling burden entirely onto external clients, increasing integration complexity.
- **Infinite Blocking**: Rejected because stuck worker nodes would tie up client connections indefinitely until HTTP socket timeouts occur.

---

## 5. Type Safety & State Modeling in Java 21

### Decision
Represent idempotency states, lock results, and cache outcomes using Java 21 sealed interfaces and record types:
```java
public sealed interface IdempotencyState permits Pending, Processing, Resolved, Failed {}
public sealed interface LockResult permits Acquired, InFlight, Mismatch, Reclaimed {}
```
Dispatching and transition validation MUST use `switch` expressions with compile-time pattern matching exhaustiveness checks.

### Rationale
- **Constitution Compliance**: Enforces Principle III (Compile-Time Exhaustion & Type Safety). No unhandled states can exist at compile time.
- **Elimination of Defensive Nulls**: Sealed types force every handler to explicitly define behavior for all valid states.

### Alternatives Considered
- **Standard Java Enums / String status codes**: Rejected due to lack of payload-carrying capacity and inability to attach specific state context (e.g., `Resolved(int statusCode, byte[] body)`).

