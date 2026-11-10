# Implementation Plan: Milestone 2 - Declarative Saga Coordinator & WAL Engine

**Branch**: `002-saga-coordinator` | **Date**: 2026-11-10 | **Spec**: [specs/002-saga-coordinator/spec.md](spec.md)

**Input**: Feature specification from `specs/002-saga-coordinator/spec.md`

---

## Summary

Build the core declarative Saga Orchestration Engine and PostgreSQL Write-Ahead Log (WAL) Journal Store. The engine coordinates distributed financial workflows with guaranteed forward progress and state durability. Every step's intent is persisted to `saga_steps` using `PESSIMISTIC_WRITE` locks prior to any outbound network execution. A 3-step reference pipeline (Debit $\rightarrow$ Credit $\rightarrow$ Ledger) demonstrates the complete forward path, integrated with the Idempotency Gate from Milestone 1.

---

## Technical Context

**Language/Version**: Java 21 LTS (Virtual Threads, Sealed Interfaces, Pattern Matching Records)  
**Primary Dependencies**: Spring Boot 3.3.5, Spring Data JPA, Hibernate 6, Redisson 3.37.0, Jackson, RestAssured  
**Storage**: PostgreSQL 16 (Relational ACID WAL journal: `saga_instances`, `saga_steps`), Redis 7 (Distributed locking)  
**Testing**: JUnit 5, AssertJ, Awaitility, RestAssured, Testcontainers 1.20.6 (PostgreSQL 16 + Redis 7 singleton containers)  
**Target Platform**: Linux / Containerized Cloud Microservices  
**Project Type**: Spring Boot Financial Infrastructure Service / Orchestration Proxy  
**Performance Goals**: Forward 3-step saga execution under 250ms p95; cached replay under 50ms p99; support >500 concurrent active sagas  
**Constraints**: Zero double-spend; WAL committed prior to network call; compile-time exhaustive state handling; no unhandled exceptions  
**Scale/Scope**: Multi-tenant partitioning (`tenant_id`); JSONB payload storage up to 2MB  

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle / Rule | Compliance Status | Implementation Architecture Reference |
| :--- | :--- | :--- |
| **I. Zero Double-Spend & Financial Invariance** | **PASS** | Every saga is initiated through the Idempotency Gate with SHA-256 payload digest. Every step intent is committed to PostgreSQL in `saga_steps` via `Propagation.REQUIRES_NEW` before outbound execution. |
| **II. Strict Backward Compensation & Zombie-Proofing** | **PASS** | Forward step interfaces define action signatures and compensation placeholders. Step records record lease expiration and unique step IDs for monotonic fencing. Full compensation rollback execution scheduled for Milestone 3. |
| **III. Compile-Time Exhaustion & Type Safety** | **PASS** | State modeled with Java 21 sealed interfaces: `SagaState` (`Pending`, `Executing`, `Completed`, `Failed`, `Compensating`) and `SagaStepState` (`NotStarted`, `Executing`, `Completed`, `Failed`, `Compensated`). All transitions evaluated with pattern matching `switch` without `default`. |
| **IV. Test-Driven & Chaos-Verified** | **PASS** | Testcontainers singleton harness (`AbstractIntegrationTest`) using real PostgreSQL 16 and Redis 7 containers. Contract and failure tests required before merging. |
| **V. Observability & Immutable Audit Trail** | **PASS** | `AuditLogger` records structured JSON events (`SAGA_STARTED`, `STEP_EXECUTING`, `STEP_COMPLETED`, `SAGA_COMPLETED`, `SAGA_FAILED`) with correlation IDs and timestamps. |

---

## Project Structure

### Documentation (`specs/002-saga-coordinator/`)

```text
specs/002-saga-coordinator/
├── plan.md              # This implementation plan
├── research.md          # Phase 0 architectural research & sealed state machine design
├── data-model.md        # Relational schema DDL, JPA entities, and sealed hierarchies
├── quickstart.md        # Scenario walkthroughs and curl verification examples
├── contracts/
│   └── saga-api.yaml    # OpenAPI 3.1 specification for transfers and saga inspection
└── checklists/
    └── requirements.md  # Specification quality checklist
```

### Source Code (`src/`)

```text
src/
├── main/
│   ├── java/io/deltaflow/
│   │   ├── saga/
│   │   │   ├── domain/
│   │   │   │   ├── SagaState.java             # Java 21 sealed interface for saga instance lifecycle
│   │   │   │   ├── SagaStepState.java         # Java 21 sealed interface for step lifecycle
│   │   │   │   ├── SagaInstance.java          # JPA Entity mapped to saga_instances
│   │   │   │   ├── SagaStep.java              # JPA Entity mapped to saga_steps
│   │   │   │   └── SagaExecutionContext.java  # Immutable runtime step-to-step context
│   │   │   ├── repository/
│   │   │   │   ├── SagaInstanceRepository.java # Spring Data JPA with PESSIMISTIC_WRITE queries
│   │   │   │   └── SagaStepRepository.java     # Spring Data JPA for step journal tracking
│   │   │   ├── builder/
│   │   │   │   ├── SagaDefinition.java        # Declarative workflow contract
│   │   │   │   ├── SagaStepDefinition.java    # Step action and compensation functional definitions
│   │   │   │   └── SagaBuilder.java           # Fluent builder API
│   │   │   ├── service/
│   │   │   │   ├── SagaJournalStore.java      # WAL persistence service (Propagation.REQUIRES_NEW)
│   │   │   │   └── SagaCoordinator.java       # Orchestration engine executing steps sequentially
│   │   │   ├── demo/
│   │   │   │   ├── TransferSagaConfig.java    # 3-step transfer saga definition registration
│   │   │   │   └── service/
│   │   │   │       ├── DebitService.java      # Step 1: Debit account mock service
│   │   │   │       ├── CreditService.java     # Step 2: Credit account mock service
│   │   │   │       └── LedgerService.java     # Step 3: Ledger entry mock service
│   │   │   └── api/
│   │   │       ├── TransferController.java    # POST /api/v1/transfers endpoint
│   │   │       ├── SagaInspectorController.java # GET /api/v1/sagas/{id} & /steps endpoints
│   │   │       └── dto/
│   │   │           ├── TransferRequestDto.java
│   │   │           ├── TransferResponseDto.java
│   │   │           ├── SagaInstanceDto.java
│   │   │           └── SagaStepDto.java
│   │   └── idempotency/                       # Milestone 1 package (reused via IdempotencyFilter)
│   └── resources/
│       └── db/migration/
│           ├── V1__create_idempotency_records.sql # Milestone 1 migration
│           └── V2__create_saga_tables.sql         # Milestone 2 migration
└── test/
    └── java/io/deltaflow/
        ├── AbstractIntegrationTest.java           # Singleton Testcontainers (PostgreSQL + Redis)
        └── saga/
            ├── builder/
            │   └── SagaBuilderTest.java           # Unit tests for fluent saga definition
            └── integration/
                ├── SagaForwardExecutionIntegrationTest.java # 3-step forward execution verification
                ├── SagaWalPersistenceIntegrationTest.java   # Verifies WAL is written before step execution
                └── SagaIdempotentReplayIntegrationTest.java # Idempotency gate integration & deduping
```

**Structure Decision**: Single modular Spring Boot project adhering to the existing packaging structure. The `saga` module resides cleanly alongside `idempotency`, integrating via Spring services and dependency injection without circular dependencies.

---

## Complexity Tracking

> No constitution violations or unjustified architectural patterns. Design adheres strictly to Principles I–V.
