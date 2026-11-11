# Implementation Plan: Backward Compensation, Crash Recovery & Zombie Fencing

**Branch**: `003-backward-compensation` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-backward-compensation/spec.md`

---

## Summary

Implement Milestone 3 of DeltaFlow: an automated reverse rollback compensation engine, monotonic fencing token zombie protection, and an autonomous crash recovery scanner. When downstream steps fail (e.g. credit rejection), completed steps are deterministically compensated in strict reverse order (refunding debits) with full write-ahead logging in PostgreSQL. Monotonic fencing tokens prevent late-arriving "zombie" executions from mutating state, and a scheduled recovery scanner reclaims expired in-flight leases across process crashes to guarantee zero stranded funds.

---

## Technical Context

**Language/Version**: Java 21 LTS (utilizing sealed interfaces, record patterns, and pattern-matching `switch`)

**Primary Dependencies**: Spring Boot 3.3.5, Spring Data JPA, Hibernate, Redisson 3.37.0, Jackson 2.17

**Storage**: PostgreSQL 16 (`saga_instances`, `saga_steps`, `idempotency_records` using `PESSIMISTIC_WRITE` and `SKIP LOCKED`), Redis 7 (Distributed lock coordination)

**Testing**: JUnit 5, AssertJ, RestAssured, Testcontainers 1.20.6 (PostgreSQL 16, Redis 7), Awaitility

**Target Platform**: Linux / Containerized JVM 21

**Project Type**: High-consistency financial orchestration library and API gateway

**Performance Goals**: < 500ms complete compensation rollback; < 20ms cached idempotent replay

**Constraints**: Strict backward compensation order (Principle II); monotonic fencing rejection (Principle II); zero double-spend or duplicate executions (Principle I)

**Scale/Scope**: Multi-tenant transactional API, horizontal cluster support with database-level skip-locked lease recovery

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Requirement | Plan Alignment | Status |
|-----------|-------------|----------------|--------|
| **I. Zero Double-Spend & Financial Invariance** | State transitions recorded in PostgreSQL WAL before external execution; dual-stage locking; terminal responses cached. | All compensations log `COMPENSATING` before execution and persist `COMPENSATED` in WAL; terminal idempotency record updated. | **PASS** |
| **II. Strict Backward Compensation & Zombie-Proofing** | Deterministic backward compensation; monotonic fencing tokens; zombie steps rejected. | Reverse sequential compensation (N-1 down to 0); monotonic 64-bit fencing tokens on `saga_steps`; stale responses rejected. | **PASS** |
| **III. Compile-Time Exhaustion & Type Safety** | Sealed interfaces and records for states and events; exhaustive pattern-matching switches with zero default catch-alls. | `SagaState` and `SagaStepState` extended with `Compensating` and `Compensated` records; compiler-checked switch dispatchers. | **PASS** |
| **IV. Test-Driven & Chaos-Verified** | Tests written before production logic; validated against containerized PostgreSQL and Redis. | Unit tests for builder/fencing, end-to-end integration tests using Testcontainers for compensation, replay, zombie rejection, and recovery. | **PASS** |
| **V. Observability & Immutable Audit Trail** | Structured JSON audit logging for all lifecycle transitions. | Emits `SAGA_COMPENSATING`, `STEP_COMPENSATING`, `STEP_COMPENSATED`, `SAGA_COMPENSATED`, `ZOMBIE_STEP_REJECTED`, `RECOVERY_CLAIMED`, `RECOVERY_COMPLETED`. | **PASS** |

---

## Project Structure

### Documentation (this feature)

```text
specs/003-backward-compensation/
├── spec.md              # Feature specification
├── plan.md              # Implementation plan (this file)
├── research.md          # Phase 0 technical decisions
├── data-model.md        # Phase 1 schema changes and state machines
├── contracts/           # Phase 1 API specifications
│   └── transfers-api.md # API failure, compensation & inspection contracts
├── checklists/          # Quality validation checklists
│   └── requirements.md  # Spec validation checklist
└── quickstart.md        # Runnable validation scenarios
```

### Source Code (repository root)

```text
src/main/resources/db/migration/
└── V2__add_fencing_token_to_saga_steps.sql            # Flyway migration adding fencing_token and indexes

src/main/java/io/deltaflow/saga/
├── domain/
│   ├── SagaState.java                                # Add Compensated record to sealed interface
│   ├── SagaStepState.java                            # Add Compensating record to sealed interface
│   ├── SagaInstance.java                             # JPA entity mapping
│   └── SagaStep.java                                 # Add fencingToken field and getters/setters
├── builder/
│   ├── SagaBuilder.java                              # Fluent step compensation registration
│   ├── SagaStepDefinition.java                       # Step definition holding compensation handler
│   └── SagaDefinition.java                           # Immutable saga definition
├── repository/
│   ├── SagaInstanceRepository.java                   # Add findExpiredInFlightForUpdate query
│   └── SagaStepRepository.java                       # Add findBySagaIdAndStepIndex query
├── service/
│   ├── SagaCoordinator.java                          # Reverse compensation engine & fencing token verification
│   ├── SagaJournalStore.java                         # WAL methods for compensation & lease renewal
│   └── SagaRecoveryScanner.java                      # Scheduled daemon scanning expired leases on boot & periodic
└── demo/
    ├── TransferSagaConfig.java                       # Register compensation handlers for debit/credit/ledger
    ├── service/DebitService.java                     # Debit execution & reverse refund compensation
    ├── service/CreditService.java                    # Credit execution & reverse debit compensation
    └── service/LedgerService.java                    # Ledger execution & void/reverse journal compensation

src/test/java/io/deltaflow/saga/
├── integration/
│   ├── SagaBackwardCompensationIntegrationTest.java  # US1: Injected credit failure triggers debit refund
│   ├── SagaCrashRecoveryIntegrationTest.java         # US2: Startup scanner recovers orphaned expired leases
│   └── SagaZombieFencingIntegrationTest.java         # US3: Rejects late-arriving responses with stale fencing token
└── builder/
    └── SagaBuilderCompensationTest.java              # US4: Verifies declarative compensation configuration
```

---

## Complexity Tracking

No constitutional violations or unjustified complexities identified. All designs adhere directly to Principles I through V.
