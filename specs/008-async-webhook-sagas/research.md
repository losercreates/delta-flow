# Research: Event-Driven Messaging & Asynchronous Webhook Sagas

**Feature Branch**: `008-async-webhook-sagas`  
**Date**: 2026-11-18  
**Spec Reference**: [spec.md](./spec.md)

---

## 1. Objectives & Architectural Decisions

This research document resolves technical selections and architectural patterns for Milestone 8:
1. **Pluggable Message Broker Outbox Dispatcher**: Selecting Kafka client / Spring Kafka integration, partition key strategy, traceparent header propagation, and Dead-Letter Queue (DLQ) mechanics.
2. **Suspended Sagas State Machine & Resource Yielding**: Designing `SUSPENDED` and `TIMED_OUT` state modeling in Java 21 sealed hierarchies, thread yield mechanics, and lock lease relinquishment.
3. **Cryptographic Webhook Ingress**: Specifying HMAC-SHA256 signature verification, anti-replay timestamp tolerance, idempotency gate deduplication, and lock re-acquisition.
4. **Timeout Detection & Automatic Compensation**: Extending `SagaRecoveryScanner` with transactional pessimistic locking on expired suspended sagas.

---

## 2. Topic 1: Message Broker Integration (Kafka & Outbox Dispatching)

### Context & Requirements
DeltaFlow's Transactional Outbox pattern currently stores domain events in `outbox_events` and utilizes a polling relay worker (`OutboxPublisher`). We need an enterprise-grade message broker implementation of `DomainEventPublisher` that sends events to partitioned topics, carries W3C trace contexts, and handles unrecoverable publish errors via a Dead-Letter Queue (DLQ).

### Decision: Spring Kafka with Testcontainers Redpanda / Kafka
- **Client Library**: `org.springframework.kafka:spring-kafka:3.2.4` (compatible with Spring Boot 3.3.5).
- **Test Harness**: `org.testcontainers:kafka:1.20.6` or `org.testcontainers:redpanda:1.20.6`. Redpanda is selected as the primary Testcontainers harness because it initializes in $<2$ seconds (compared to 15+ seconds for standard Zookeeper/KRaft Kafka), while providing 100% Kafka wire protocol compatibility.
- **Partitioning Strategy**:
  - Partition Key: `aggregateId` (which maps to `sagaId` for saga events and `transactionId`/`idempotencyKey` for payment events).
  - Guarantees: Kafka's default murmur2 partition hash ensures all events for the same saga instance land on the identical topic partition, providing strict chronological ordering per transaction.
- **Header Propagation**:
  - OpenTelemetry W3C headers injected as byte arrays into `org.apache.kafka.common.header.Headers`:
    - `traceparent`: `00-<traceId>-<spanId>-<flags>`
    - `X-Event-Type`: String event type
    - `X-Tenant-ID`: Multi-tenant identifier
    - `X-Aggregate-ID`: Aggregate identifier
- **Dead-Letter Queue (DLQ)**:
  - Default topic: `deltaflow.domain-events`
  - DLQ topic: `deltaflow.domain-events.dlq`
  - When an event fails to publish after `deltaflow.outbox.max-retries` (default: 5), it is sent to the DLQ topic with error metadata headers (`X-Exception-Message`, `X-Original-Topic`) and flagged in the database as `DEAD_LETTER`.

---

## 3. Topic 2: Suspended Sagas (`WAITING_FOR_EVENT`) & Thread Model

### Context & Requirements
Long-running financial workflows (such as 3D-Secure customer OTP challenges, KYC compliance reviews, or asynchronous ACH/SEPA clearance) can take minutes or hours. Holding Java Virtual Threads or Redis distributed lock leases during this time would starve system resources and prevent legitimate concurrent operations.

### Decision: State-Machine Yielding & Lease Relinquishment
- **Sealed State Extensions**:
  ```java
  // In SagaState.java
  record Suspended(
      UUID sagaId,
      String tenantId,
      int stepIndex,
      String stepName,
      String awaitingEventName,
      Instant stepDeadline,
      Instant suspendedAt
  ) implements SagaState {}

  record TimedOut(
      UUID sagaId,
      String tenantId,
      int stepIndex,
      String stepName,
      Instant timedOutAt
  ) implements SagaState {}
  ```
- **Declarative Step Definition**:
  Extend `SagaStepDefinition<I>`:
  ```java
  public record SagaStepDefinition<I>(
      String name,
      BiFunction<SagaExecutionContext, I, ?> action,
      BiConsumer<SagaExecutionContext, I> compensation,
      String circuitBreakerName,
      String retryName,
      List<ParallelBranchDefinition<I>> parallelBranches,
      boolean isAsyncWait,
      String awaitingEventName,
      Duration timeout
  )
  ```
  Add builder method:
  `stepBuilder.asyncWait(String name, String awaitingEventName, Duration timeout, BiConsumer<SagaExecutionContext, I> compensation)`
- **Thread Yield Mechanism**:
  When `step.isAsyncWait()` is true:
  1. Record step status as `SUSPENDED` in `saga_steps`.
  2. Record saga instance status as `SUSPENDED` with `awaiting_event_name` and `step_deadline` in `saga_instances`.
  3. Relinquish the distributed lock (`leaseManager.releaseLease(sagaId, tenantId)`).
  4. The virtual thread exits the `executeSaga` method cleanly, freeing memory and Loom carrier thread capacity.

---

## 4. Topic 3: Secure Webhook Ingress Architecture

### Context & Requirements
External parties (gateways, risk engines) deliver webhook notifications when external processing completes. These endpoints must resist spoofing, man-in-the-middle tampering, replay attacks, and duplicate delivery races.

### Decision: Cryptographic HMAC-SHA256 & Idempotency Gating
- **Endpoint**: `POST /api/v1/sagas/{sagaId}/events/{eventName}`
- **Security Headers**:
  - `X-Webhook-Signature`: Hex-encoded HMAC-SHA256 digest of `${timestamp}.${rawBody}` computed with a tenant shared secret.
  - `X-Webhook-Timestamp`: Unix epoch milliseconds.
  - `Idempotency-Key`: Unique delivery key from the webhook provider (or auto-synthesized from `sha256(rawBody)`).
- **Verification Flow**:
  1. **Anti-Replay**: Check that `|currentTime - webhookTimestamp| <= 300s`. Reject if expired (HTTP 401).
  2. **Signature Verification**: Compute `HmacSHA256("${timestamp}.${rawBody}", secretKey)`. Perform constant-time equality check (`MessageDigest.isEqual`) against `X-Webhook-Signature`. Reject if mismatched (HTTP 401).
  3. **Idempotency Gate**: Submit to `IdempotencyCoordinator`. Duplicate deliveries return cached 200 OK immediately without modifying state.
  4. **Lock & Fencing Re-acquisition**: Acquire distributed lock on `sagaId`. Increment fencing token.
  5. **State Validation**: Query saga instance.
     - If status is `SUSPENDED` and awaiting event matches `{eventName}`: Proceed to resumption.
     - If status is `COMPENSATED`, `TIMED_OUT`, or `FAILED`: Reject with HTTP 409 Conflict.
     - If status is `COMPLETED`: Return HTTP 200 OK (idempotent replay).
  6. **Step Progression**: Record webhook payload in `saga_steps`, update step to `COMPLETED`, update saga to `EXECUTING`, and resume remaining forward steps.

---

## 5. Topic 4: Asynchronous Timeout Recovery & Backward Compensation

### Context & Requirements
If an external webhook fails to arrive within the configured timeout window (`stepDeadline`), the saga must not hang indefinitely. It must automatically roll back in compliance with Constitution Principle II.

### Decision: Scanner Extension with Pessimistic Locking
- **Query**:
  ```sql
  SELECT s FROM SagaInstance s 
  WHERE s.status = 'SUSPENDED' 
    AND s.stepDeadline < :now
  ```
  with `PESSIMISTIC_WRITE` lock mode and `FOR UPDATE SKIP LOCKED`.
- **Compensation Execution**:
  1. Transition saga instance from `SUSPENDED` to `TIMED_OUT`.
  2. Obtain fresh fencing token.
  3. Load all previously completed steps (`SagaStep` where `status = 'COMPLETED'`) ordered by `stepIndex DESC`.
  4. Invoke each step's registered compensation callback in reverse LIFO order.
  5. Record step rollback state in `saga_steps`.
  6. Transition saga instance to `COMPENSATED`.
  7. Emit `SAGA_COMPENSATED` domain event to Transactional Outbox for broker distribution.

---

## 6. Constitution Compliance Matrix

| Principle | Architectural Mechanism | Verification Strategy |
| :--- | :--- | :--- |
| **I. Zero Double-Spend** | Distributed locks re-acquired before resuming suspended sagas; idempotency gate guards webhook ingress | Concurrent webhook tests submitting identical payloads simultaneously |
| **II. Strict Compensation & Zombie Proofing** | Fencing tokens incremented on resume; recovery scanner triggers LIFO compensation on timeout; late webhooks rejected (409) | Tests simulating late webhook arriving after timeout compensation settles |
| **III. Compile-Time Exhaustion** | `SagaState.Suspended` and `SagaState.TimedOut` added to sealed interface; exhaustive switch dispatchers | Compiler validation; no wildcard default branches |
| **IV. Test-Driven & Chaos-Verified** | Redpanda/Kafka Testcontainers used for all integration tests | Integration test suite verifying Kafka message delivery and DLQ routing |
| **V. Observability & Audit Trail** | W3C `traceparent` headers injected into Kafka records; webhook signatures and timeouts audit-logged | Assert OpenTelemetry headers in Kafka records and audit logs |
