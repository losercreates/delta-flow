# Implementation Plan: Transactional Outbox & Resilience Policies

**Branch**: `004-transactional-outbox` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-transactional-outbox/spec.md`

---

## Summary

Implement Milestone 4 of DeltaFlow:
1. **Transactional Outbox Pattern**: Store domain events in PostgreSQL (`outbox_events`) within the exact same database transaction as saga state mutations, and asynchronously publish them to downstream destinations using an autonomous `OutboxPublisher` background relay daemon (`FOR UPDATE SKIP LOCKED`) with at-least-once delivery and dead-letter quarantine for poison pills.
2. **Resilience Policies**: Integrate Resilience4j circuit breakers and exponential backoff retries with jitter on external saga step invocations to absorb transient network blips and fail-fast into saga compensation when external dependencies are down.
3. **Structured Concurrency**: Leverage Java 21 `StructuredTaskScope` and Virtual Threads to orchestrate parallel saga branches concurrently with automatic sibling cancellation (`ShutdownOnFailure`) and zero thread leaks.

---

## Technical Context

**Language/Version**: Java 21 LTS (utilizing Virtual Threads and Structured Concurrency)  
**Primary Dependencies**: Spring Boot 3.3.5, Spring Data JPA, Hibernate 6.5, Resilience4j 2.2.0 (`resilience4j-spring-boot3`), Redisson 3.37.0, Jackson, Flyway  
**Storage**: PostgreSQL 16 (for relational write-ahead logging and outbox tables) and Redis 7 (for distributed locking)  
**Testing**: JUnit 5, AssertJ, Testcontainers (PostgreSQL 16, Redis 7), Resilience4j Test Module  
**Target Platform**: Linux server / Docker container runtime  
**Project Type**: Multi-tenant distributed idempotency and saga orchestration engine  
**Performance Goals**: Sub-second outbox relay polling latency; <10ms virtual thread spawn and coordination overhead; sub-millisecond fast-fail on open circuit breakers  
**Constraints**: Zero dual-write hazards; atomic outbox persistence; strict sibling cancellation on branch failure; zero orphaned virtual threads; non-blocking dead-letter quarantine  
**Scale/Scope**: Multi-tenant transactional API, horizontal cluster support with database-level skip-locked outbox polling  

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Requirement | Verification Method | Status |
|---|---|---|---|
| **I. Zero Double-Spend & Financial Invariance** | Outbox events persisted atomically in the same PostgreSQL transaction as saga state mutations. | Transaction boundaries encompass both `saga_instances` and `outbox_events`. Integration tests verify rollback discards outbox events. | **PASS** |
| **II. Strict Backward Compensation & Zombie-Proofing** | Circuit breaker trips and retry exhaustions trigger deterministic backward compensation. | Injected failures verify coordinator initiates reverse rollback; parallel branch failures cancel sibling tasks. | **PASS** |
| **III. Compile-Time Exhaustion & Type Safety** | Outbox event statuses and resilience states modeled using sealed records and enums with exhaustive pattern matching. | Compiler-enforced switch dispatchers without default catch-alls. | **PASS** |
| **IV. Test-Driven & Chaos-Verified** | Tests written before production logic; validated against containerized PostgreSQL and Redis. | End-to-end integration tests using Testcontainers for outbox publishing, dead-letter quarantine, circuit breakers, and structured concurrency. | **PASS** |
| **V. Observability & Immutable Audit Trail** | Structured JSON audit logging for all outbox and resilience lifecycle transitions. | Emits `OUTBOX_RECORDED`, `OUTBOX_PUBLISHED`, `OUTBOX_RETRY`, `OUTBOX_DEAD_LETTER`, `CIRCUIT_BREAKER_STATE_CHANGE`. | **PASS** |

---

## Project Structure

### Documentation (this feature)

```text
specs/004-transactional-outbox/
├── spec.md              # Feature specification
├── plan.md              # This file (implementation plan)
├── research.md          # Phase 0: Technical decisions and trade-offs
├── data-model.md        # Phase 1: Database schema, entities, and state machines
├── quickstart.md        # Phase 1: End-to-end runnable validation scenarios
├── checklists/
│   └── requirements.md  # Quality checklist
├── contracts/
│   └── outbox-api.yaml  # OpenAPI contract for outbox inspection
└── tasks.md             # Phase 2: Actionable dependency-ordered tasks (generated via /speckit-tasks)
```

### Source Code Layout

```text
src/
├── main/
│   ├── java/io/deltaflow/
│   │   ├── outbox/
│   │   │   ├── domain/
│   │   │   │   ├── OutboxEvent.java                  # JPA entity for outbox_events
│   │   │   │   └── OutboxStatus.java                 # Sealed/Enum status: PENDING, IN_FLIGHT, PUBLISHED, DEAD_LETTER
│   │   │   ├── repository/
│   │   │   │   └── OutboxEventRepository.java        # Spring Data JPA with findPendingForUpdate (SKIP LOCKED)
│   │   │   ├── service/
│   │   │   │   ├── OutboxService.java                # Transactional WAL helper for recording outbox events
│   │   │   │   ├── OutboxPublisher.java              # Scheduled background relay daemon polling & dispatching
│   │   │   │   └── DomainEventPublisher.java         # Interface for message dispatchers / consumers
│   │   │   └── api/
│   │   │       ├── OutboxInspectorController.java    # REST API for outbox inspection
│   │   │       └── dto/
│   │   │           └── OutboxEventDto.java           # DTO for outbox event queries
│   │   ├── resilience/
│   │   │   ├── ResilienceConfig.java                 # Spring configuration for Resilience4j registries
│   │   │   └── ResiliencePolicyRegistry.java         # Programmatic decorator helper for saga steps
│   │   └── saga/
│   │       ├── builder/
│   │       │   ├── ParallelBranchDefinition.java     # Concurrent branch definition model
│   │       │   ├── SagaBuilder.java                  # Extended with .parallelStep() and .withResilience()
│   │       │   └── SagaStepDefinition.java           # Stores resilience policies and parallel branch list
│   │       └── service/
│   │           └── SagaCoordinator.java              # Executes parallel branches via StructuredTaskScope & records outbox events
│   └── resources/
│       └── db/migration/
│           └── V4__create_outbox_events_table.sql    # Flyway migration creating outbox_events and indexes
└── test/
    └── java/io/deltaflow/
        ├── outbox/
        │   └── integration/
        │       ├── OutboxPublishingIntegrationTest.java # US1: Verifies atomic persistence & relay delivery
        │       └── OutboxDeadLetterIntegrationTest.java # US4: Verifies poison pill quarantine
        ├── resilience/
        │   └── integration/
        │       └── SagaResilienceIntegrationTest.java   # US2: Verifies retries and circuit breaker trips
        └── saga/
            └── integration/
                └── SagaStructuredConcurrencyIntegrationTest.java # US3: Verifies parallel branch execution & cancellation
```

---

## Complexity Tracking

No constitutional violations or unjustified complexities identified. All designs adhere strictly to Principles I through V.
