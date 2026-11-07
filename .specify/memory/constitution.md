# DeltaFlow Project Constitution
<!-- High-Consistency Distributed Idempotency & Saga Orchestration for Financial APIs -->

## Core Principles

### I. Zero Double-Spend & Financial Invariance (NON-NEGOTIABLE)
Every financial debit/credit operation MUST be idempotent and guarded by a cryptographic SHA-256 payload digest verified against the `Idempotency-Key` header to detect payload tampering or replay attacks. No state transition may cross a microservice boundary without prior persistence in PostgreSQL following the Write-Ahead Log (WAL) pattern. In-flight requests MUST acquire a dual-stage distributed lock (`Pending` -> `Processing` -> `Resolved` / `Failed`) with explicit lease expiration. Duplicate requests with matching keys MUST return cached terminal responses immediately without triggering downstream execution.
*Rationale: Financial systems cannot tolerate duplicate transactions or lost intermediate state resulting from network partitioning or client-side retries.*

### II. Strict Backward Compensation & Zombie-Proofing
Every forward saga step MUST define a corresponding, strictly deterministic backward compensation action. All external RPC and HTTP calls MUST use monotonic fencing tokens and lease timeouts. Delayed responses or executions from slow "zombie" steps arriving after compensation has completed MUST be unconditionally rejected by downstream participants and the orchestrator.
*Rationale: In distributed financial topologies, network delays often cause timeouts while remote calls succeed out of band. Deterministic compensations and fencing tokens prevent split-brain states and stranded balance deductions.*

### III. Compile-Time Exhaustion & Type Safety
All state machine states (`Pending`, `Executing`, `Compensating`, `Completed`, `Failed`) and saga events MUST be modeled using Java 21 sealed interfaces and record patterns. Transition dispatchers MUST enforce pattern-matching `switch` statements with full compile-time exhaustion checks. Default `switch` catch-alls that could silently swallow unhandled states are strictly forbidden.
*Rationale: Undefined or unhandled state transitions are primary vectors for frozen transactions and irrecoverable financial leakage.*

### IV. Test-Driven & Chaos-Verified (NON-NEGOTIABLE)
TDD is strictly mandatory: contract and failure tests MUST be written before production logic. All database and caching interactions MUST be validated against real containerized environments via Testcontainers (PostgreSQL, Redis). The system MUST pass automated Toxiproxy chaos tests simulating at least 30% packet drops, artificial latency spikes, and worker process termination mid-saga without incurring orphaned debits or double spends.
*Rationale: Mocks cannot reliably capture race conditions, lease expirations, or distributed deadlock hazards under network degradation.*

### V. Observability & Immutable Audit Trail
Every state transition, lock acquisition, lease renewal, and compensation attempt MUST be written to an append-only, immutable audit log aligned with SOC2 and PCI-DSS compliance standards. All logs MUST be structured JSON and carry correlation IDs and OpenTelemetry trace contexts (`traceparent`) propagated across all distributed saga hops.
*Rationale: Forensic auditing, real-time debugging, and financial compliance mandate complete traceability for every monetary decision.*

## Technical Constraints & Target Platform

- **Runtime & Core Language**: Java 21 LTS utilizing Virtual Threads (Project Loom) and Structured Concurrency for parallel branches.
- **Framework & Persistence**: Spring Boot 3 with Spring Data JPA and Hibernate, employing `PESSIMISTIC_WRITE` database-level locks for transaction isolation.
- **Storage Infrastructure**:
  - PostgreSQL for relational ACID state storage (`saga_instances`, `saga_steps`, `idempotency_records`).
  - Redis for sub-millisecond distributed lock management and lease coordination.
- **Resilience**: Resilience4j for circuit breakers, bulkheads, and bounded retry policies.
- **Frontend Dashboard**: React + TypeScript with an interactive DAG visualizer (React Flow / Cytoscape) and WebSocket/SSE real-time streaming.

## Project Delivery Milestones & Scope Boundaries

Development proceeds strictly through the following six sequential milestones. Each milestone represents an independently testable increment governed by explicit verification gates:

- **Milestone 1: Foundations & Idempotency Gate (Proxy Layer)**
  - Dual-stage idempotency locking (`Pending`, `Processing`, `Resolved`, `Failed`) in Redis with PostgreSQL fallback.
  - SHA-256 cryptographic request body hashing to prevent payload tampering.
  - Lease timeout auto-expiration and cached terminal response delivery.
  - *Gate*: Concurrent requests with identical idempotency keys execute exactly once; duplicates return cached payloads.

- **Milestone 2: Declarative Saga Coordinator & WAL Engine (Forward Path)**
  - Java 21 sealed interface state machine and transition engine.
  - PostgreSQL WAL Journal Store (`saga_instances`, `saga_steps`, `idempotency_records`) using `PESSIMISTIC_WRITE`.
  - Sequential 3-step forward execution pipeline (Debit Service -> Credit Service -> Ledger Service).
  - *Gate*: Happy-path money transfer successfully commits all steps, recording immutable journal entries before network dispatches.

- **Milestone 3: Backward Compensation, Crash Recovery & Zombie Fencing**
  - Reverse rollback execution engine triggering deterministic compensations on step failure.
  - Fencing tokens and lease validation preventing late-arriving zombie steps from corrupting state.
  - Startup crash recovery scanner scanning uncompleted leases on boot and resuming forward or backward progress.
  - *Gate*: Injected credit service failure triggers automatic debit refund; process termination mid-flight resumes and settles cleanly on restart.

- **Milestone 4: Transactional Outbox & Resilience Policies**
  - Transactional Outbox pattern for publishing domain events without two-phase commit or dual-write hazards.
  - Resilience4j circuit breakers and exponential backoff retries on transient external dependencies.
  - Java 21 Structured Concurrency coordinating parallel branches (e.g., customer notification + ledger write) safely.
  - *Gate*: Transient network dropouts trip circuit breakers without leaking worker threads or hanging transactions.

- **Milestone 5: Operator Management Dashboard (React + TypeScript)**
  - Interactive Saga Flow DAG visualizer with real-time color-coded step statuses (Green: Success, Yellow: Processing, Red: Compensating/Failed).
  - Idempotency Inspector by key, payload hash, and status.
  - Manual Intervention Console allowing operators to force-retry or mark stuck ambiguous transactions as compensated.
  - WebSocket / SSE streaming for live state updates.
  - *Gate*: Operators can inspect active sagas in real time and safely resolve halted steps from the web console.

- **Milestone 6: Chaos Testing, OpenTelemetry & Production Hardening**
  - Toxiproxy chaos injection test suite simulating 30% packet loss and partition scenarios.
  - Automated Invariant Verification proving zero balance discrepancies across 10,000 chaos-injected runs.
  - End-to-end OpenTelemetry distributed tracing across all HTTP/gRPC hops.
  - Exportable SOC2/PCI-DSS immutable audit trail logs.
  - *Gate*: 100% financial balance integrity maintained under chaos testing with zero orphaned debits.

## Governance

- **Supremacy**: This Constitution supersedes all ad-hoc architectural decisions, code reviews, and task implementations. Any design divergence must be formally ratified before code merge.
- **Amendment Procedure**: Modifications to principles or milestones require an amended proposal documenting the architectural rationale, migration impact, and version bump.
- **Versioning Policy**: Semantic versioning strictly enforced:
  - **MAJOR**: Changes that remove, weaken, or redefine core principles or backward compatibility guarantees.
  - **MINOR**: Additions of new governance principles, sections, or expanded milestones.
  - **PATCH**: Non-semantic clarifications, wording refinements, or typo fixes.
- **Compliance Review**: All Pull Requests, `spec.md`, `plan.md`, and `tasks.md` artifacts generated via SpecKit MUST explicitly cross-reference and adhere to the non-negotiable principles defined herein.

**Version**: 1.0.0 | **Ratified**: 2026-11-07 | **Last Amended**: 2026-11-07
