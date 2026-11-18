# Implementation Plan: Event-Driven Messaging & Asynchronous Webhook Sagas

**Branch**: `008-async-webhook-sagas` | **Date**: 2026-11-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-async-webhook-sagas/spec.md`

---

## Summary

Milestone 8 extends DeltaFlow from synchronous distributed transaction execution into an asynchronous event-driven platform. It introduces two core distributed systems capabilities:
1. **Pluggable Event-Driven Outbox Dispatching**: A partitioned message broker publisher (Apache Kafka / Redpanda) streaming domain events with W3C `traceparent` headers and Dead-Letter Queue (DLQ) fault isolation.
2. **Asynchronous Suspended Sagas (`WAITING_FOR_EVENT`)**: Workflows requiring human-in-the-loop verification (e.g. 3D-Secure, fraud review) yield their virtual threads and distributed lock leases, entering a persistent `SUSPENDED` state in PostgreSQL. A cryptographically authenticated webhook endpoint (`POST /api/v1/sagas/{sagaId}/events/{eventName}`) validates HMAC-SHA256 signatures and idempotency keys to resume forward execution. Sagas exceeding their `stepDeadline` are automatically rolled back via backward compensation by the `SagaRecoveryScanner`.

---

## Technical Context

**Language/Version**: Java 21 LTS (Project Loom Virtual Threads, Sealed Interfaces, Pattern Matching)  
**Primary Dependencies**: Spring Boot 3.3.5, Spring Kafka 3.2.4, Redisson 3.37.0, Resilience4j 2.2.0, OpenTelemetry SDK 1.39.0, Micrometer Prometheus  
**Storage**: PostgreSQL 16 (Relational WAL, `PESSIMISTIC_WRITE`, `FOR UPDATE SKIP LOCKED`), Redis 7 (Distributed locking & lease coordination)  
**Testing**: JUnit 5, AssertJ, Testcontainers 1.20.6 (`PostgreSQLContainer`, `GenericContainer` Redis, `KafkaContainer`/`RedpandaContainer`), Toxiproxy 2.1.7  
**Target Platform**: Linux server / Containerized runtime (`eclipse-temurin:21-jre-jammy`)  
**Project Type**: High-consistency financial orchestration engine and API gateway  
**Performance Goals**: <100ms resource release upon entering `SUSPENDED`, <500ms webhook resume latency, zero orphaned balance deductions  
**Constraints**: Zero double-spend, strict backward compensation, monotonic fencing tokens, cryptographic non-repudiation  
**Scale/Scope**: 10,000+ concurrent suspended sagas without platform thread or lock starvation  

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Compliance Details |
| :--- | :--- | :--- |
| **I. Zero Double-Spend & Financial Invariance** | ✅ PASS | Webhook callbacks are guarded by the Idempotency Gate; distributed locks and monotonic fencing tokens are re-acquired before resuming state execution. |
| **II. Strict Backward Compensation & Zombie-Proofing** | ✅ PASS | Timeout expiration triggers automatic LIFO backward compensation; late-arriving zombie webhooks are rejected with HTTP 409 Conflict based on fencing tokens and terminal state checks. |
| **III. Compile-Time Exhaustion & Type Safety** | ✅ PASS | Sealed interfaces `SagaState` and `SagaStepState` extended with `Suspended` and `TimedOut` records; state transition switches enforce exhaustive pattern matching without default fallbacks. |
| **IV. Test-Driven & Chaos-Verified** | ✅ PASS | All integration tests validate real broker interactions using Kafka/Redpanda Testcontainers and Toxiproxy network partitions. |
| **V. Observability & Immutable Audit Trail** | ✅ PASS | Outbox broker messages inject W3C `traceparent` headers; webhook ingress records, signature verifications, suspensions, and compensations are written to the append-only audit trail. |

---

## Project Structure

### Documentation (this feature)

```text
specs/008-async-webhook-sagas/
├── spec.md              # Feature requirements and user scenarios
├── plan.md              # Implementation plan (this document)
├── research.md          # Architectural decisions & research findings
├── data-model.md        # Relational schemas and sealed domain records
├── quickstart.md        # Curl validation walkthrough scenarios
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── contracts/
    ├── webhook-ingress-api.yaml      # OpenAPI 3.0 Webhook Ingress specification
    └── event-messaging-schema.json   # Outbox Kafka broker message envelope schema
```

### Source Code Impact

```text
src/
├── main/
│   ├── java/io/deltaflow/
│   │   ├── outbox/
│   │   │   ├── service/
│   │   │   │   ├── DomainEventPublisher.java      # Existing interface
│   │   │   │   └── KafkaDomainEventPublisher.java # New Kafka broker publisher with DLQ
│   │   │   └── domain/
│   │   │       └── KafkaEventEnvelope.java        # Broker record envelope with traceparent
│   │   ├── saga/
│   │   │   ├── domain/
│   │   │   │   ├── SagaState.java                 # Extended with Suspended & TimedOut records
│   │   │   │   ├── SagaStepState.java             # Extended with Suspended record
│   │   │   │   └── WebhookIngressRecord.java      # Audit and replay tracking entity
│   │   │   ├── builder/
│   │   │   │   ├── SagaStepDefinition.java        # Extended with asyncWait metadata
│   │   │   │   └── SagaBuilder.java               # asyncWait builder DSL
│   │   │   ├── service/
│   │   │   │   ├── SagaCoordinator.java           # Suspension handling and resume execution
│   │   │   │   ├── SagaJournalStore.java          # Suspended state persistence & querying
│   │   │   │   └── SagaRecoveryScanner.java       # Expired suspended saga timeout detection
│   │   │   ├── webhook/
│   │   │   │   ├── WebhookSecurityService.java    # HMAC-SHA256 signature & timestamp validation
│   │   │   │   └── WebhookIngressController.java  # POST /api/v1/sagas/{id}/events/{eventName}
│   │   │   └── repository/
│   │   │       ├── SagaInstanceRepository.java    # Query for expired suspended instances
│   │   │       └── WebhookIngressRepository.java  # Repository for webhook records
│   │   └── config/
│   │       └── KafkaBrokerConfig.java             # Spring Kafka Producer/Consumer configuration
│   └── resources/
│       ├── application.yml                        # Kafka topics, broker toggle, webhook secrets
│       └── db/migration/
│           └── V8__async_sagas_and_webhooks.sql   # Schema alterations
└── test/
    ├── java/io/deltaflow/
    │   ├── outbox/integration/
    │   │   ├── KafkaOutboxPublishingIntegrationTest.java
    │   │   └── KafkaOutboxDeadLetterIntegrationTest.java
    │   └── saga/integration/
    │       ├── SagaAsyncSuspensionIntegrationTest.java
    │       ├── SagaWebhookResumptionIntegrationTest.java
    │       ├── SagaWebhookSecurityIntegrationTest.java
    │       └── SagaAsyncTimeoutCompensationIntegrationTest.java
    └── resources/
        └── application-test.yml                   # Testcontainer Kafka bootstrap properties
```

---

## Complexity Tracking

*No constitutional violations. All designs strictly comply with Core Principles I–V.*

| Decision | Why Selected | Alternatives Rejected |
| :--- | :--- | :--- |
| **Virtual Thread Yielding vs Thread Parking** | Releasing the thread and distributed lock lease entirely avoids thread leaks, lock contention, and connection pool exhaustion during long waits. | `Thread.sleep` or `CountDownLatch` rejected because parking virtual threads for minutes/hours holds database transactions and locks in memory. |
| **HMAC-SHA256 Signatures with Timestamp** | Standard industry pattern (used by Stripe, GitHub, Adyen) providing cryptographic payload integrity and anti-replay protection. | Unsigned webhooks or simple API keys rejected due to lack of tamper resistance against payload modification. |
| **Redpanda Testcontainers** | 100% Kafka wire compatibility with sub-2-second boot time, drastically accelerating CI test suite execution. | Standard Zookeeper Kafka container rejected due to 15-20s startup overhead. |
