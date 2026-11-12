# Feature Specification: Transactional Outbox & Resilience Policies

**Feature Branch**: `004-transactional-outbox`  
**Created**: 2026-11-12  
**Status**: Draft  
**Input**: User description: "Implement Milestone 4: Transactional Outbox & Resilience Policies. Build the Transactional Outbox pattern with an outbox table and scheduled publisher for reliable domain event delivery without dual-write hazards, integrate Resilience4j circuit breakers and exponential backoff retries for transient external dependencies, and leverage Java 21 Structured Concurrency (StructuredTaskScope) with Virtual Threads for coordinated parallel execution branches (e.g., customer notification alongside ledger recording)."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Transactional Outbox for Dual-Write-Free Event Publishing (Priority: P1) 🎯 MVP

As an event-driven financial microservice, when a saga completes or transitions through intermediate states (e.g. step completed, saga compensated, saga resolved), I want domain events to be atomically persisted in the relational database within the exact same transaction as the state mutation, and asynchronously published by an autonomous outbox relay daemon, so that the system completely eliminates dual-write anomalies (e.g., database commit succeeding but message broker publish failing or vice-versa) with guaranteed at-least-once delivery.

**Why this priority**: Core architectural invariant for distributed financial integrity (Constitution Principle I & V). Emitting messages directly to a network broker in the middle of a database transaction introduces split-brain risks and ghost events if the database rollbacks after the network dispatch.

**Independent Test**: Can be verified by executing a transfer saga. The database transaction must persist the saga state and outbox event records atomically. If the transaction rolls back, zero outbox records exist. When the transaction commits, the background outbox relay acquires unpublished records via pessimistic locking, publishes them, and transitions their status to `PUBLISHED` with timestamps.

**Acceptance Scenarios**:

1. **Given** an active saga step execution or completion, **When** state changes are committed to PostgreSQL, **Then** one or more domain events are inserted into `outbox_events` within the same database transaction.
2. **Given** a database rollback during step execution, **When** the transaction aborts, **Then** no outbox event is persisted, guaranteeing no phantom events are ever emitted.
3. **Given** pending outbox records in state `PENDING`, **When** the scheduled outbox publisher scans the outbox table using `FOR UPDATE SKIP LOCKED`, **Then** it atomically claims unprocessed batches and dispatches them in sequential creation order.
4. **Given** successful event transmission, **When** the destination confirms delivery, **Then** the outbox record status transitions to `PUBLISHED` with `published_at` recorded.

---

### User Story 2 - Circuit Breakers & Bounded Exponential Retries for External Dependencies (Priority: P1)

As a distributed payment orchestrator, when communicating with third-party banking gateways, credit validation services, or external ledgers that experience transient packet drops, latency spikes, or intermittent outages, I want automated circuit breakers and exponential backoff retries to absorb minor blips without failing the transfer, and quickly trip to `OPEN` when downstream services are truly down, so that the system prevents thread pool exhaustion and fails fast into deterministic backward compensation.

**Why this priority**: Network unreliability is the norm in financial networks. Unbounded retries or indefinite blocking cause cascading failure across the entire cluster. Bounded retries absorb transient network noise while circuit breakers protect system capacity (Constitution Principle IV).

**Independent Test**: Can be verified by injecting simulated transient network failures into an external service (e.g., 503 or socket timeout). DeltaFlow retries up to $N$ configured attempts with exponential backoff before failing. Under repeated sustained errors exceeding the failure threshold (e.g., 50%), the circuit breaker trips to `OPEN`, immediately rejecting subsequent attempts with fast-fail errors without initiating network I/O, and triggering reverse compensation cleanly.

**Acceptance Scenarios**:

1. **Given** an external dependency call encountering a transient network glitch or 5xx response, **When** the step executes, **Then** the system automatically retries the call up to $N$ times with exponential backoff and jitter before treating the step as failed.
2. **Given** a high error rate exceeding the configured threshold (e.g. $\ge 50\%$ over a sliding window of 10 requests), **When** subsequent requests arrive, **Then** the circuit breaker transitions to `OPEN` and immediately rejects executions without making outbound network requests.
3. **Given** an `OPEN` circuit breaker, **When** the configured wait duration elapses, **Then** the circuit breaker transitions to `HALF_OPEN` and permits a bounded set of trial requests to probe whether the external service has recovered.
4. **Given** a call rejected by an `OPEN` circuit breaker or exhausted retries, **When** the failure is reported to the saga coordinator, **Then** the coordinator immediately halts forward progression and triggers backward compensation for prior completed steps.

---

### User Story 3 - Coordinated Parallel Branches with Java 21 Structured Concurrency (Priority: P2)

As a workflow author, when a saga step requires multiple independent operations to execute concurrently (for example, sending customer push/email notifications while simultaneously writing to an analytical ledger or audit log), I want to coordinate these parallel branches using Java 21 `StructuredTaskScope` and Virtual Threads, so that all concurrent subtasks execute in parallel with strict lifecycle bounds, clean cancellation propagation if one branch fails, and zero thread leaks.

**Why this priority**: Improves end-to-end throughput for non-dependent operations while guaranteeing that child threads never outlive their parent execution scope or become orphaned zombies in the background.

**Independent Test**: Can be verified by defining a parallel multi-branch step containing branch A (customer notification) and branch B (ledger update). Both branches execute concurrently on virtual threads. If branch A fails or times out, branch B is automatically cancelled via `StructuredTaskScope.ShutdownOnFailure`, and the parent scope throws the originating failure to trigger compensation.

**Acceptance Scenarios**:

1. **Given** a saga step configured with parallel branch actions, **When** the step is dispatched, **Then** each branch executes concurrently on lightweight Java 21 Virtual Threads within a unified `StructuredTaskScope`.
2. **Given** all parallel branches complete successfully, **When** the scope joins, **Then** the coordinator aggregates results from all branches into the saga execution context and advances forward.
3. **Given** any branch in the scope throws an exception or times out, **When** the failure occurs, **Then** the scope immediately signals shutdown to all sibling branches, cancels outstanding work, and bubbles the failure to the coordinator for backward rollback.

---

### User Story 4 - Outbox Poison Pill Quarantine & Dead Letter Handling (Priority: P3)

As a platform operations engineer, if an individual outbox event encounters persistent fatal delivery errors (such as unparseable corrupted payload or permanent remote contract rejection) exceeding the maximum retry threshold, I want the outbox relay to quarantine the problematic event into a `DEAD_LETTER` state with diagnostic error information, so that poison pills do not perpetually block the publishing pipeline for healthy messages.

**Why this priority**: Prevents Head-of-Line (HoL) blocking where a single poisoned record starves the outbox processor and prevents subsequent valid customer transactions from being broadcast.

**Independent Test**: Can be verified by inserting an outbox event with simulated fatal publishing failure. After reaching the maximum retry count (e.g., 5 attempts), the event status changes to `DEAD_LETTER` with error details captured, and subsequent healthy outbox events continue to be published normally.

**Acceptance Scenarios**:

1. **Given** an outbox event that repeatedly fails publishing, **When** its retry count reaches the configured maximum, **Then** the system marks the event status as `DEAD_LETTER` and persists the terminal error message.
2. **Given** an event in `DEAD_LETTER` status, **When** subsequent scheduled scan cycles run, **Then** the outbox publisher skips the dead-lettered event and processes remaining `PENDING` events.
3. **Given** a quarantined event, **When** marked as `DEAD_LETTER`, **Then** the system logs a structured audit event `OUTBOX_DEAD_LETTER` containing event ID, aggregate type, and error details for operator inspection.

---

### Edge Cases

- **Outbox Relay Process Crash**: If the outbox publishing process crashes mid-batch while sending an event, the unacknowledged transaction times out, the database row lock is released, and the next scan cycle re-acquires the event for at-least-once delivery.
- **Circuit Breaker Open During Compensation**: If a backward compensation step itself calls an external service whose circuit is open, the coordinator falls back to exponential backoff or flags the saga for administrative operator intervention, preserving the audit trail.
- **Parallel Branch Partial Failure**: If Branch 1 succeeds before Branch 2 fails within a `StructuredTaskScope`, cancellation is sent to Branch 2, and the coordinator ensures compensation runs for any side-effects produced by Branch 1.
- **Clock Skew on Outbox Scheduling**: Using relative poll intervals (`fixedDelay`) and database server time (`CURRENT_TIMESTAMP`) avoids outbox starvation caused by local worker clock drift.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST create an append-only `outbox_events` table in PostgreSQL with columns for `event_id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `status`, `retry_count`, `error_details`, `created_at`, and `published_at`.
- **FR-002**: System MUST persist domain events to `outbox_events` within the same atomic database transaction as the saga state mutation.
- **FR-003**: System MUST provide an autonomous `OutboxPublisher` background service that polls `outbox_events` using `FOR UPDATE SKIP LOCKED` to prevent worker contention in clustered deployments.
- **FR-004**: System MUST publish domain events at-least-once and update event status to `PUBLISHED` upon confirmed dispatch.
- **FR-005**: System MUST configure Resilience4j circuit breakers on external outbound step calls with configurable failure rate threshold, slow call duration threshold, and sliding window size.
- **FR-006**: System MUST configure Resilience4j retry policies on transient network exceptions with exponential backoff, multiplier, and randomized jitter.
- **FR-007**: When a circuit breaker is in `OPEN` state, system MUST fail fast with a call-not-permitted exception without making network calls.
- **FR-008**: System MUST transition circuit breakers from `OPEN` to `HALF_OPEN` after a configurable wait duration, allowing trial calls to test service recovery.
- **FR-009**: System MUST provide a parallel branch step builder in `SagaBuilder` allowing concurrent execution of multiple branch actions using Java 21 `StructuredTaskScope`.
- **FR-010**: System MUST execute parallel branch actions on Virtual Threads, ensuring zero thread starvation on blocking I/O operations.
- **FR-011**: If any subtask in a parallel branch fails, system MUST immediately cancel sibling subtasks and fail fast using `StructuredTaskScope.ShutdownOnFailure`.
- **FR-012**: If an outbox event exceeds maximum retry attempts (default: 5), system MUST transition its status to `DEAD_LETTER` and continue processing subsequent events.
- **FR-013**: System MUST emit structured JSON audit log events for outbox and resilience lifecycle transitions: `OUTBOX_RECORDED`, `OUTBOX_PUBLISHED`, `OUTBOX_RETRY`, `OUTBOX_DEAD_LETTER`, `CIRCUIT_BREAKER_STATE_CHANGE`.
- **FR-014**: System MUST propagate OpenTelemetry correlation contexts (`traceparent`, `tenantId`, `idempotencyKey`) through outbox event headers and parallel virtual thread branches.
- **FR-015**: Outbox polling interval, batch size, and retry thresholds MUST be configurable via Spring application properties (`deltaflow.outbox.*` and `deltaflow.resilience.*`).

---

### Key Entities

- **OutboxEvent**: Immutable representation of a published or pending domain event persisted in PostgreSQL, containing unique event ID, aggregate reference (`saga_id`), event type, serialized JSON payload, status (`PENDING`, `PUBLISHED`, `DEAD_LETTER`), retry counter, and timestamps.
- **ResiliencePolicyConfig**: Operational configuration defining circuit breaker sliding window size, failure rate percentage, wait duration in open state, and retry backoff intervals for external service clients.
- **ParallelBranchExecution**: Representation of a coordinated group of concurrent actions executing within a unified `StructuredTaskScope` bound to a single saga step.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of saga lifecycle state transitions successfully persist corresponding outbox events in the same database transaction with zero dual-write discrepancy.
- **SC-002**: 100% of pending outbox events are published within 2 scan cycles of transaction commit under standard operating conditions.
- **SC-003**: Transient downstream network faults (under 3 consecutive failures) are absorbed by exponential backoff retries without failing the parent customer transfer.
- **SC-004**: When downstream failure rate exceeds 50%, circuit breaker trips to `OPEN` within 1 sliding window cycle, reducing downstream call traffic to 0 during the open duration.
- **SC-005**: Parallel branch steps using Virtual Threads complete in the duration of the slowest individual branch ($O(\max(t_1, t_2))$ instead of $O(t_1 + t_2)$) without leaking active threads when a branch fails.

---

## Assumptions

- PostgreSQL 16 remains the authoritative relational store for both saga write-ahead logging and outbox records.
- In-process or external message consumption targets (such as Spring ApplicationEventPublisher or mock message relays) serve as the outbox destination for integration testing.
- Default outbox scan interval is 1 second with a default batch size of 50 events.
- Default Resilience4j sliding window size is 10 calls with a 50% failure rate trip threshold and 5-second open duration.
- Structured concurrency utilizes Java 21 `StructuredTaskScope` (Preview API or production Loom constructs) with virtual thread-per-task executors.

