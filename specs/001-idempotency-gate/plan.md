# Implementation Plan: Milestone 1 - Foundations & Distributed Idempotency Gate

**Branch**: `001-idempotency-gate` | **Date**: 2026-11-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-idempotency-gate/spec.md`

## Summary

Build the foundational distributed idempotency proxy layer for DeltaFlow in Java 21 / Spring Boot 3. The system intercepts incoming HTTP payment requests carrying an `Idempotency-Key` header, buffers the body to calculate an immutable SHA-256 payload digest, acquires a distributed lock in Redis (with PostgreSQL fallback), enforces a dual-stage lifecycle (`PENDING` -> `PROCESSING` -> `RESOLVED` / `FAILED`), and serves cached terminal responses immediately on duplicate calls to prevent double-spends.

## Technical Context

**Language/Version**: Java 21 LTS (Virtual Threads Loom, Sealed Interfaces, Pattern Matching switch).  
**Primary Dependencies**: Spring Boot 3.3.x, Spring Data JPA / Hibernate, Redisson 3.31+ / Lettuce, Jackson, Flyway.  
**Storage**: PostgreSQL 16 (Relational ACID persistence for `idempotency_records`), Redis 7 (Distributed locking and contention notifications).  
**Testing**: JUnit 5, AssertJ, Testcontainers (PostgreSQL & Redis), RestAssured.  
**Target Platform**: Linux server / Containerized cloud deployment.  
**Project Type**: Spring Boot Web Service / Proxy Middleware Filter.  
**Performance Goals**: Sub-15ms cached response latency; 1,000 requests/sec with zero duplicate executions under concurrency.  
**Constraints**: Zero financial double-spends (Constitution Principle I); maximum buffered request payload: 2MB; NTP clock skew tolerance: <100ms.  
**Scale/Scope**: Multi-tenant key scoping `(tenant_id, idempotency_key)`; configurable 30-second lease timeout.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle / Governance Gate | Status | Compliance Verification |
| :--- | :---: | :--- |
| **I. Zero Double-Spend & Financial Invariance** | **PASS** | SHA-256 payload hashing prevents key tampering; relational WAL persistence and dual-stage locking ensure exactly-once execution. |
| **II. Strict Backward Compensation & Zombie-Proofing** | **PASS** | Lease timeouts auto-expire abandoned requests; lock tokens and fencing prevent stale in-flight writes from corrupting resolved state. |
| **III. Compile-Time Exhaustion & Type Safety** | **PASS** | State machine states (`Pending`, `Processing`, `Resolved`, `Failed`) modeled with Java 21 sealed interfaces and pattern matching switch. |
| **IV. Test-Driven & Chaos-Verified** | **PASS** | Testcontainers (PostgreSQL + Redis) mandatory for all integration and concurrency test suites. |
| **V. Observability & Immutable Audit Trail** | **PASS** | Structured JSON logging with correlation IDs for lock acquisitions, cache hits, digest mismatches, and lease expirations. |

## Project Structure

### Documentation (this feature)

```text
specs/001-idempotency-gate/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command output)
├── data-model.md        # Phase 1 output (/speckit-plan command output)
├── quickstart.md        # Phase 1 output (/speckit-plan command output)
├── contracts/           # Phase 1 output (/speckit-plan command output)
│   └── idempotency-api.yaml
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
src/
├── main/
│   ├── java/io/deltaflow/
│   │   ├── DeltaFlowApplication.java
│   │   ├── common/
│   │   │   ├── audit/AuditLogger.java
│   │   │   └── exception/IdempotencyConflictException.java
│   │   ├── idempotency/
│   │   │   ├── domain/
│   │   │   │   ├── IdempotencyRecord.java
│   │   │   │   ├── IdempotencyState.java
│   │   │   │   └── LockAcquisitionResult.java
│   │   │   ├── repository/
│   │   │   │   └── IdempotencyRecordRepository.java
│   │   │   ├── lock/
│   │   │   │   ├── DistributedLockManager.java
│   │   │   │   ├── RedisDistributedLock.java
│   │   │   │   └── PostgresAdvisoryLockFallback.java
│   │   │   ├── filter/
│   │   │   │   ├── CachedBodyHttpServletRequest.java
│   │   │   │   ├── CachedBodyHttpServletResponse.java
│   │   │   │   └── IdempotencyFilter.java
│   │   │   ├── service/
│   │   │   │   ├── IdempotencyCoordinator.java
│   │   │   │   └── PayloadHasher.java
│   │   │   └── api/
│   │   │       ├── IdempotencyInspectorController.java
│   │   │       └── dto/IdempotencyRecordDto.java
│   │   └── demo/
│   │       ├── api/PaymentController.java
│   │       └── dto/PaymentDto.java
│   └── resources/
│       ├── application.yml
│       └── db/migration/
│           └── V1__create_idempotency_records.sql
└── test/
    └── java/io/deltaflow/
        ├── idempotency/
        │   ├── filter/IdempotencyFilterTest.java
        │   ├── lock/RedisDistributedLockTest.java
        │   ├── service/PayloadHasherTest.java
        │   └── integration/
        │       ├── IdempotencyGateIntegrationTest.java
        │       └── DistributedLockConcurrencyTest.java
        └── TestDeltaFlowApplication.java
```

**Structure Decision**: Standard Spring Boot 3 modular package structure. The idempotency core (`idempotency/`) is isolated from common utilities and demonstration endpoints (`demo/`), enabling it to be packaged as a reusable framework library in subsequent milestones while providing an out-of-the-box executable server.

## Complexity Tracking

> **Constitution Check has zero violations. No complexity exemptions required.**

| Violation | Why Needed | Simpler Alternative Rejected Because |
| :--- | :--- | :--- |
| *None* | *N/A* | *N/A* |
