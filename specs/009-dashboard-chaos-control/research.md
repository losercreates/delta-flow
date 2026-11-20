# Phase 0: Research & Technical Decisions: Real-Time Streaming & Chaos Control in Operator Dashboard

## Decision 1: Real-Time Live Streaming Protocol (SSE vs. WebSockets)

- **Decision**: Standardize on Server-Sent Events (SSE) using Spring Boot's `SseEmitter` with client-side `EventSource` and automatic exponential reconnects.
- **Rationale**:
  - DeltaFlow dashboard telemetry is predominantly unidirectional (server-to-client state transition broadcasts and telemetry metrics updates).
  - Client actions (chaos injection, invariant verification, manual intervention, DLQ replay) are command-oriented REST calls requiring strict HTTP semantics, idempotency, authentication, and audit tracing.
  - SSE operates seamlessly over standard HTTP/1.1 and HTTP/2 without custom framing, handshake overhead, or proxy-buffering issues common to WebSockets in enterprise banking environments.
  - DeltaFlow already includes a robust `DashboardEventBroadcaster` using `CopyOnWriteArrayList<SseEmitter>` with 15-second heartbeat intervals. We extend this with new event types (`CHAOS_INJECTED`, `CHAOS_HEALED`, `CIRCUIT_BREAKER_TRANSITIONED`, `INVARIANT_CHECKED`, `DLQ_REPLAYED`).
- **Alternatives Considered**:
  - *WebSockets (STOMP / SockJS)*: Rejected due to unnecessary bidirectional framing complexity, difficulties traversing enterprise proxies, and lack of native browser reconnection semantics compared to `EventSource`.
  - *Long Polling*: Rejected due to high server overhead, latency jitter, and excessive database/cache read amplification.

---

## Decision 2: Chaos Control Architecture & Safe Toxiproxy Interfacing

- **Decision**: Implement a dedicated `ChaosControlService` backed by `ToxiproxyHarness` (for test/staging containerized setups) and Resilience4j `CircuitBreakerRegistry` (for application-level resilience control), featuring a safe no-op / simulation fallback when Toxiproxy sidecars are not reachable.
- **Rationale**:
  - Toxiproxy is the industry gold standard for TCP-level network fault injection (packet loss, downstream latency, slicer drops, TCP partition).
  - In production or local development without Toxiproxy running, API requests must fail gracefully or report simulation mode rather than throwing uncaught connection errors.
  - Combining TCP network fault injection with Resilience4j circuit breaker state transitions (`transitionToOpenState()`, `transitionToClosedState()`, `transitionToHalfOpenState()`) gives operators and automated test suites complete control over failure injection and recovery paths.
  - Every chaos injection must be recorded with an operator audit trail (`AuditLogService`), preventing unauthorized or untracked degradation.
- **Alternatives Considered**:
  - *Chaos Mesh / ChaosBlade*: Rejected because they require Kubernetes cluster-level administrative privileges, making local unit/integration tests and lightweight Docker environments cumbersome.
  - *Bytecode manipulation / Byteman*: Rejected due to JVM agent overhead and risk of unrecoverable JVM crash in test containers.

---

## Decision 3: Financial Invariant Verification Architecture & Telemetry Integration

- **Decision**: Expose the existing `FinancialInvariantVerifier` through the dashboard API and broadcast verification completion events over the live SSE stream, returning a comprehensive report with double-spend auditing, orphaned debit detection, and debit/credit balance conservation.
- **Rationale**:
  - DeltaFlow already implements `FinancialInvariantVerifier` which audits `totalDebits`, `totalCredits`, and `totalRefunds`, alongside detecting orphaned debits in failed sagas.
  - Surfacing this capability in the dashboard with an on-demand trigger (`POST /api/v1/invariants/verify`) and real-time SSE notification (`INVARIANT_CHECKED`) allows operators to visually verify financial consistency immediately after running chaos experiments.
  - The verification report is rendered with distinct color-coded visual cards (Passed = Emerald, Violations = Rose) with expandable details on any detected variance.
- **Alternatives Considered**:
  - *Continuous background invariant cron*: While background auditing is useful, on-demand verification with immediate interactive feedback is required for interactive chaos testing workflows.

---

## Decision 4: Dead-Letter Queue (DLQ) Forensics & Manual Replay Engine

- **Decision**: Extend `DashboardService` with DLQ query and replay capabilities (`GET /api/v1/dashboard/dlq`, `POST /api/v1/dashboard/dlq/{eventId}/replay`), which resets poisoned outbox records back to `PENDING` with retry count cleared and publishes an audit event.
- **Rationale**:
  - In Milestone 8, outbox events exceeding maximum delivery retries are marked as `DEAD_LETTER` in PostgreSQL.
  - Tier-2 operators need visual inspection of dead-lettered events (payload, target topic, failure cause, attempt count) and the ability to trigger safe replays after fixing downstream network or broker issues.
  - Replaying a DLQ event must re-acquire lock leases, update state safely, and broadcast an `OUTBOX_REPLAYED` live event to the dashboard.
- **Alternatives Considered**:
  - *Kafka-only DLQ CLI tools*: Rejected because operators need unified web console access with correlation to saga IDs and business transactions.

---

## Decision 5: Frontend Tabbed Console & Reactive State Architecture

- **Decision**: Add a dedicated "Chaos & Invariants" tab to the React dashboard (`App.tsx`) with three dedicated visual modules:
  1. **Chaos Injector Panel**: Target selector (e.g. `postgres-proxy`, `redis-proxy`, `payment-gateway`), fault toggles (Latency, Packet Loss, Slicer), and "Heal All" button.
  2. **Circuit Breakers Console**: Interactive cards displaying failure rate, buffered calls, and manual State Transition buttons (Trip Open / Force Close / Half-Open).
  3. **Financial Invariants Auditor**: One-click verification trigger with real-time pass/fail metric cards, double-spend counters, and violation inspection.
  4. **DLQ Inspector**: Modal / sub-view listing dead-lettered messages with payload viewer and one-click replay action.
- **Rationale**:
  - Seamlessly integrates with existing UI components (`TelemetryHeader`, `SagaFlowDag`, `IdempotencyInspector`), utilizing Tailwind CSS and Lucide React icons.
  - Reactive updates triggered automatically by the existing `useDashboardStream` hook when `CHAOS_INJECTED`, `CIRCUIT_BREAKER_TRIPPED`, or `INVARIANT_CHECKED` events arrive.
