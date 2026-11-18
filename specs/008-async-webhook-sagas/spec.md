# Feature Specification: Event-Driven Messaging & Asynchronous Webhook Sagas

**Feature Branch**: `008-async-webhook-sagas`  
**Created**: 2026-11-18  
**Status**: Draft  
**Input**: User description: "Event-Driven Messaging & Asynchronous Webhook Sagas: Extend DeltaFlow with pluggable message broker outbox dispatching (Apache Kafka / RabbitMQ) and asynchronous suspended sagas (WAITING_FOR_EVENT). Wire the Transactional Outbox publisher to emit partitioned domain events to Kafka topics with traceparent propagation and a Dead-Letter Queue (DLQ). Implement declarative asynchronous saga steps (e.g., 3DS verification, fraud review, external payment callbacks) where sagas yield virtual threads, release locks, and enter SUSPENDED state with configurable timeouts. Implement secure webhook ingress (POST /api/v1/sagas/{sagaId}/events/{eventName}) with HMAC-SHA256 signature verification, idempotency protection, and lock re-acquisition to resume execution. Provide automatic timeout recovery triggering backward compensation if webhooks do not arrive within deadline, verified with Testcontainers integration tests."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Pluggable Message Broker Outbox Dispatching with Partitioned Topics (Priority: P1) 🎯 MVP

As a platform architect or downstream service developer, when monetary state transitions occur in DeltaFlow (such as saga initiation, step completions, or compensation triggers), I want the Transactional Outbox to reliably publish domain events to partitioned message broker topics (e.g., Apache Kafka / RabbitMQ) with distributed trace context and dead-letter routing, so that downstream microservices receive ordered, at-least-once event notifications without distributed transaction overhead.

**Why this priority**: Core event-driven foundation. Financial microservices require reliable asynchronous event distribution to decouple services (e.g., notification systems, analytics pipelines, accounting ledgers) without dual-write inconsistencies, strictly fulfilling Constitution Principle I & V.

**Independent Test**: Can be tested by executing a payment saga; verifying that the outbox polling worker extracts pending events, publishes them to the configured message broker topic with the correct partition key (`sagaId` or `tenantId`) and W3C `traceparent` header, and verifies that persistent publish failures route to a Dead-Letter Queue (DLQ).

**Acceptance Scenarios**:

1. **Given** a successfully committed transaction containing outbox records, **When** the outbox publisher scans pending events, **Then** it dispatches each event to the target message broker topic partitioned by `sagaId` or `tenantId` to guarantee strict chronological ordering per transaction.
2. **Given** an active OpenTelemetry trace context, **When** an outbox event is dispatched to the broker, **Then** the message headers contain the W3C `traceparent` and correlation metadata.
3. **Given** an unrecoverable broker error or repeated delivery failure exceeding maximum retry attempts, **When** the outbox publisher reaches the retry threshold, **Then** the poisoned event is safely moved to a Dead-Letter Queue (DLQ) topic and flagged as `DEAD_LETTER` in the database without halting publisher progress on subsequent events.

---

### User Story 2 - Declarative Suspended Sagas (`WAITING_FOR_EVENT`) & Resource Yielding (Priority: P1) 🎯 MVP

As a financial workflow author, when designing a long-running transaction that depends on external asynchronous actions (such as a 3D-Secure customer OTP challenge, credit bureau scoring, or asynchronous bank settlement), I want to declare asynchronous waiting steps with bounded timeouts, so that the saga coordinator gracefully releases its virtual thread and distributed lock lease, transitioning the workflow to a persistent `SUSPENDED` state rather than consuming execution threads during prolonged wait periods.

**Why this priority**: Essential architectural capability for human-in-the-loop and multi-stage payment flows. Real-world financial operations cannot block application threads or hold database locks for minutes or hours while waiting for human interaction or third-party webhooks.

**Independent Test**: Can be tested by defining a saga containing an asynchronous step (`Step.asyncWait(...)`); triggering the saga; asserting that upon reaching the async step, the step transitions to `SUSPENDED` / `WAITING_FOR_EVENT`, the distributed lock lease is released in Redis, the virtual thread terminates cleanly, and the instance state in PostgreSQL records the suspension timestamp and timeout deadline.

**Acceptance Scenarios**:

1. **Given** a saga definition with an asynchronous step, **When** forward execution reaches that step, **Then** the coordinator marks the step and saga instance as `SUSPENDED`, records the expected event name and expiration deadline in the journal, and releases the distributed lock.
2. **Given** a suspended saga instance, **When** inspecting running threads and Redis locks, **Then** no active threads are parked or blocked, and the distributed lock lease for that transaction key is released so other operations are not blocked.
3. **Given** a suspended saga, **When** an operator inspects the saga state via API or dashboard, **Then** the state clearly displays `SUSPENDED`, the awaiting event name, and the remaining time until timeout.

---

### User Story 3 - Cryptographic Webhook Ingress & Safe Saga Resumption (Priority: P2)

As a third-party payment gateway or external verification provider, when an asynchronous external event completes (such as 3DS approval or fraud check verification), I want to deliver a cryptographic webhook payload to DeltaFlow, so that the suspended saga securely verifies the webhook signature, re-acquires distributed locks, and resumes forward execution from the exact point of suspension.

**Why this priority**: Critical security and state progression boundary. External callback webhooks are a prime attack vector for spoofing and race conditions; they must be cryptographically verified and guarded against duplicate delivery.

**Independent Test**: Can be tested by invoking the webhook ingress endpoint (`POST /api/v1/sagas/{sagaId}/events/{eventName}`) with valid HMAC-SHA256 signature headers; verifying that the saga validates the signature, dedupes repeated deliveries via the idempotency gate, re-acquires the distributed lock and fresh fencing token, records the webhook payload in the step journal, and advances to subsequent saga steps.

**Acceptance Scenarios**:

1. **Given** a valid webhook request signed with a shared secret, **When** received at `/api/v1/sagas/{sagaId}/events/{eventName}`, **Then** the orchestrator computes the HMAC-SHA256 digest of the request body, verifies that it matches the `X-Webhook-Signature` header, and accepts the request.
2. **Given** a webhook with an invalid or tampered signature, **When** processed by the webhook ingress endpoint, **Then** the orchestrator rejects the request immediately with HTTP 401 Unauthorized, logging a security audit event without modifying saga state.
3. **Given** an accepted webhook for a suspended saga, **When** processing begins, **Then** the coordinator re-acquires the distributed lock with an incremented fencing token, updates the step state to `COMPLETED` with the webhook result payload, and resumes executing remaining forward steps to final completion.
4. **Given** duplicate webhook deliveries for the same event, **When** received concurrently or sequentially, **Then** the idempotency gate detects the duplicate key and returns HTTP 200/201 cached acknowledgment without executing duplicate step transitions.

---

### User Story 4 - Asynchronous Timeout Expiration & Automatic Backward Compensation (Priority: P2)

As a risk officer or reliability engineer, when an external webhook or callback fails to arrive within the configured step timeout deadline, I want the system to detect the expired suspended saga and automatically initiate backward compensation across all previously completed steps, so that reserved funds and customer balances are returned to a clean, consistent state without manual human escalation.

**Why this priority**: Enforces Constitution Principle II (Strict Backward Compensation). In financial systems, unfulfilled asynchronous promises cannot leave balances deducted or orders permanently pending; timeouts must trigger guaranteed rollbacks.

**Independent Test**: Can be tested by initiating a saga with an asynchronous step configured with a short timeout; allowing the deadline to elapse without providing a webhook callback; asserting that the background recovery scanner detects the expiration, transitions the saga to `TIMED_OUT`, and executes backward compensation across preceding completed steps in reverse order.

**Acceptance Scenarios**:

1. **Given** a saga suspended in `WAITING_FOR_EVENT` whose timeout deadline has expired, **When** the scheduled recovery scanner evaluates pending sagas, **Then** it identifies the expired instance, transitions its status to `TIMED_OUT`, and initiates the backward compensation engine.
2. **Given** a timed-out saga with previously completed forward steps (e.g. debit step), **When** backward compensation runs, **Then** each completed step executes its defined compensation action (e.g. refund/credit) in LIFO order with full retry policies.
3. **Given** a late-arriving webhook that arrives after the saga has already timed out or completed compensation (zombie webhook), **When** processed by the webhook endpoint, **Then** the orchestrator rejects the callback with HTTP 409 Conflict / 410 Gone, preserving the compensated state and emitting an audit event.

---

### Edge Cases

- **Zombie Webhook Arrival**: External webhook callback arrives seconds or minutes after the saga has already timed out and completed backward compensation. Handled by validating the saga state and fencing token; late arrivals are rejected with HTTP 409/410 and do not reverse the compensation.
- **Concurrent Webhook Submission**: Two identical webhook callbacks arrive simultaneously on separate HTTP worker threads. Guarded by the idempotency gate and distributed lock on `sagaId + eventName`; exactly one succeeds in re-acquiring the lock and transitioning the step.
- **Webhook Timestamp Drift / Replay**: A malicious actor intercepts an old webhook and replays it. Guarded by validating the `X-Webhook-Timestamp` against an allowable tolerance window (e.g., $\le 300$ seconds) before verifying HMAC signature.
- **Broker Downtime During Outbox Sweep**: Message broker (Kafka/RabbitMQ) temporarily crashes or partitions while the outbox sweeper is actively publishing. Outbox entries remain in `PENDING` status with exponential backoff; no records are lost or dropped.
- **Poisoned Outbox Payload**: An event payload cannot be serialized or exceeds maximum broker message size. Exceeding max delivery retries moves the event to a dedicated Dead-Letter Queue (DLQ) with error diagnostics, preventing head-of-line blocking.
- **Process Crash While Resuming Suspended Saga**: The application crashes immediately after an external webhook is accepted but before subsequent steps finish. On startup, the recovery scanner detects the unexpired in-flight lease and resumes forward execution.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a pluggable `OutboxBrokerPublisher` interface with an implementation for message brokers (Apache Kafka / RabbitMQ) supporting partitioned topic dispatch.
- **FR-002**: System MUST inject the active OpenTelemetry W3C `traceparent` and correlation metadata into message broker record headers for all outbox dispatches.
- **FR-003**: System MUST route outbox events to a Dead-Letter Queue (DLQ) topic when delivery fails repeatedly beyond the configured maximum retry threshold.
- **FR-004**: System MUST partition outbox broker events deterministically using the `sagaId` or `tenantId` to ensure total in-order delivery per business entity.
- **FR-005**: System MUST allow saga definitions to declare asynchronous waiting steps (`Step.asyncWait`) specifying an event name and maximum duration timeout.
- **FR-006**: When executing an asynchronous waiting step, the coordinator MUST persist the step status as `SUSPENDED` in PostgreSQL, release the distributed Redis lock lease, and terminate the executing virtual thread.
- **FR-007**: System MUST record the suspension timestamp and absolute timeout deadline (`expiresAt`) in the relational journal store.
- **FR-008**: System MUST expose a secure webhook ingress endpoint at `POST /api/v1/sagas/{sagaId}/events/{eventName}` to receive external callback payloads.
- **FR-009**: Webhook ingress endpoint MUST verify cryptographic HMAC-SHA256 signatures passed in the `X-Webhook-Signature` header against the raw request body and tenant secret.
- **FR-010**: Webhook ingress endpoint MUST validate the `X-Webhook-Timestamp` header to reject replayed callbacks older than 300 seconds.
- **FR-011**: Webhook ingress endpoint MUST guard callback processing with the distributed Idempotency Gate using an idempotency key derived from the webhook payload or dedicated header.
- **FR-012**: Upon successful webhook authentication, the coordinator MUST re-acquire the distributed lock, obtain an incremented fencing token, record the callback payload in the step journal, transition step status to `COMPLETED`, and resume forward saga execution.
- **FR-013**: The background `SagaRecoveryScanner` MUST poll for suspended sagas whose `expiresAt` deadline has passed.
- **FR-014**: Expired suspended sagas MUST be automatically transitioned to `TIMED_OUT` and dispatched to the backward compensation engine to roll back all previously executed steps in LIFO order.
- **FR-015**: Webhooks arriving for sagas that have already reached `COMPENSATED`, `FAILED`, or `COMPLETED` terminal states MUST be rejected with HTTP 409 Conflict and an immutable audit log entry.
- **FR-016**: All outbox dispatches, webhook receipts, signature verification outcomes, suspensions, resumptions, and timeout compensations MUST emit structured audit events conforming to Constitution Principle V.

---

### Key Entities *(include if feature involves data)*

- **OutboxBrokerMessage**: Represents the published domain event containing event ID, aggregate type, aggregate ID, event type, payload JSON, partition key, and W3C traceparent headers.
- **DeadLetterEvent**: Represents an outbox event that failed broker transmission beyond the retry threshold, capturing error stack traces, original payload, target topic, and failure timestamp.
- **AsyncStepDefinition**: Declarative specification of an asynchronous step containing step name, expected event identifier, timeout duration, and optional compensation step.
- **SagaInstance (Extended)**: Existing entity enhanced with status enum values: `SUSPENDED`, `TIMED_OUT`, along with `awaitingEventName` and `stepDeadline` timestamp columns.
- **WebhookIngressRecord**: Audit and idempotency tracking record containing saga ID, event name, payload SHA-256 hash, signature verification status, sender timestamp, and processing outcome.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of outbox events published to Kafka/broker retain W3C `traceparent` propagation and maintain deterministic partition ordering per saga ID.
- **SC-002**: Asynchronous suspended sagas release 100% of their worker virtual threads and distributed lock leases within 100ms of entering the `SUSPENDED` state.
- **SC-003**: 100% of forged, unsigned, or timestamp-expired webhook callbacks are rejected with HTTP 401/403 before altering database state or triggering saga resumption.
- **SC-004**: A valid webhook callback delivered to a suspended saga re-acquires distributed locks and resumes forward execution with end-to-end latency under 500ms under standard loads.
- **SC-005**: 100% of suspended sagas that exceed their timeout deadline without receiving a webhook trigger automatic backward compensation with zero orphaned balance deductions.
- **SC-006**: 0% duplicate step transitions or double-spend occurrences under simulated concurrent duplicate webhook deliveries.

---

## Assumptions

- **Target Message Broker**: Apache Kafka (or Redpanda via Testcontainers in automated testing) is used as the reference broker implementation for outbox streaming, with extensible interfaces for other brokers.
- **Signature Algorithm**: External webhooks utilize standard HMAC-SHA256 signatures computed using shared secret keys configured per tenant or global secret.
- **Clock Synchronization**: Webhook sender and DeltaFlow servers synchronize time via NTP within $\pm 300$ seconds.
- **Thread Model**: Java 21 Virtual Threads execute resumed saga forward steps upon webhook reception without blocking platform carrier threads.
- **Backward Compatibility**: All existing synchronous saga definitions (from Milestones 1–7) continue to operate without modification; asynchronous steps are an opt-in declarative builder feature.
