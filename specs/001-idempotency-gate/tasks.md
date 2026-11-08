# Tasks: Milestone 1 - Foundations & Distributed Idempotency Gate

**Input**: Design documents from `/specs/001-idempotency-gate/`  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/idempotency-api.yaml`, `quickstart.md`  
**Governance**: `.specify/memory/constitution.md` (Principle I: Zero Double-Spend, Principle IV: TDD Mandatory)  
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.  

## Format: `[ID] [P?] [Story] Description with exact file path`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization, Maven build configuration, and environment setup

- [ ] T001 Initialize Spring Boot 3.3.x Maven project with Java 21 in `pom.xml` including dependencies for Spring Web, Spring Data JPA, PostgreSQL driver, Flyway, Redisson, Testcontainers (PostgreSQL, Redis), and AssertJ
- [ ] T002 [P] Configure application settings, connection pools, and lease TTL defaults (`deltaflow.idempotency.lease-ttl: 30s`) in `src/main/resources/application.yml`
- [ ] T003 [P] Configure test application settings with dynamic Testcontainers properties in `src/test/resources/application-test.yml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure, database schemas, sealed domain models, and test harnesses that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T004 Create Flyway migration in `src/main/resources/db/migration/V1__create_idempotency_records.sql` defining `idempotency_records` table with columns: `tenant_id VARCHAR(64) NOT NULL DEFAULT 'default'`, `idempotency_key VARCHAR(128) NOT NULL`, `request_hash CHAR(64) NOT NULL`, `status VARCHAR(20) NOT NULL`, `lock_token VARCHAR(64)`, `response_status_code INT`, `response_headers JSONB`, `response_payload BYTEA`, `lease_expires_at TIMESTAMPTZ NOT NULL`, composite primary key `CONSTRAINT pk_idempotency_records PRIMARY KEY (tenant_id, idempotency_key)`, and indexes `idx_idempotency_lease_expiry ON idempotency_records (status, lease_expires_at)` and `idx_idempotency_created_at ON idempotency_records (created_at)`
- [ ] T005 [P] Implement Java 21 sealed domain interfaces `IdempotencyState` permitting `Pending`, `Processing`, `Resolved`, `Failed` and `LockAcquisitionResult` in `src/main/java/io/deltaflow/idempotency/domain/IdempotencyState.java` and `src/main/java/io/deltaflow/idempotency/domain/LockAcquisitionResult.java`
- [ ] T006 [P] Implement JPA composite key `IdempotencyRecordId` and entity `IdempotencyRecord` matching PostgreSQL schema constraints in `src/main/java/io/deltaflow/idempotency/domain/IdempotencyRecordId.java` and `src/main/java/io/deltaflow/idempotency/domain/IdempotencyRecord.java`
- [ ] T007 [P] Implement Spring Data JPA repository `IdempotencyRecordRepository` in `src/main/java/io/deltaflow/idempotency/repository/IdempotencyRecordRepository.java` with pessimistic write lock method `findByTenantIdAndIdempotencyKey` using `@Lock(LockModeType.PESSIMISTIC_WRITE)`
- [ ] T008 [P] Implement base exception hierarchy in `src/main/java/io/deltaflow/common/exception/IdempotencyException.java` and conflict exceptions in `src/main/java/io/deltaflow/common/exception/IdempotencyConflictException.java`
- [ ] T009 [P] Implement structured JSON audit logger in `src/main/java/io/deltaflow/common/audit/AuditLogger.java` emitting correlation IDs, tenant IDs, idempotency keys, and state transition actions
- [ ] T010 Create base Testcontainers test harness in `src/test/java/io/deltaflow/AbstractIntegrationTest.java` configuring containerized PostgreSQL 16 and Redis 7 with dynamic registry properties

**Checkpoint**: Foundation ready - user story implementation can now begin.

---

## Phase 3: User Story 1 - Exact Replay Deduplication with Cached Response (Priority: P1) 🎯 MVP

**Goal**: Deliver the core idempotency guarantee. Intercept HTTP requests with `Idempotency-Key`, execute downstream for initial requests, store terminal response in `RESOLVED` state, and return the cached response with header `Idempotency-Status: CACHED` on exact replays without calling downstream handlers.

**Independent Test**: Send POST `/api/v1/payments` with `Idempotency-Key: pay_demo_001`. Receives 201 Created (`Idempotency-Status: DIRECT`). Resend exact same request; receives cached 201 Created (`Idempotency-Status: CACHED`) within 15ms and downstream mock service verifies 0 additional executions.

### Tests for User Story 1 (TDD - Mandatory per Constitution Principle IV) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T011 [P] [US1] Write failing integration test for initial execution and cached replay in `src/test/java/io/deltaflow/idempotency/integration/IdempotencyReplayIntegrationTest.java`

### Implementation for User Story 1

- [ ] T012 [P] [US1] Implement request and response byte buffering wrappers `CachedBodyHttpServletRequest` and `CachedBodyHttpServletResponse` in `src/main/java/io/deltaflow/idempotency/filter/CachedBodyHttpServletRequest.java` and `src/main/java/io/deltaflow/idempotency/filter/CachedBodyHttpServletResponse.java`
- [ ] T013 [US1] Implement core transaction lifecycle coordinator in `src/main/java/io/deltaflow/idempotency/service/IdempotencyCoordinator.java` managing transitions to `PENDING`, `PROCESSING`, and `RESOLVED` with response status, headers, and payload persistence
- [ ] T014 [US1] Implement HTTP servlet filter `IdempotencyFilter` in `src/main/java/io/deltaflow/idempotency/filter/IdempotencyFilter.java` intercepting `Idempotency-Key` headers, checking cached records, returning cached responses with header `Idempotency-Status: CACHED`, or executing downstream with `Idempotency-Status: DIRECT`
- [ ] T015 [P] [US1] Implement demo payment transfer controller and DTOs in `src/main/java/io/deltaflow/demo/api/PaymentController.java` and `src/main/java/io/deltaflow/demo/dto/PaymentDto.java` matching OpenAPI contract `/api/v1/payments`
- [ ] T016 [US1] Verify and pass `IdempotencyReplayIntegrationTest.java` in `src/test/java/io/deltaflow/idempotency/integration/IdempotencyReplayIntegrationTest.java` proving exact replay returns cached response without duplicate downstream execution

**Checkpoint**: At this point, User Story 1 (MVP) is fully functional and testable independently.

---

## Phase 4: User Story 2 - Payload Tampering & Digest Mismatch Protection (Priority: P2)

**Goal**: Protect against payload tampering and key collisions by computing SHA-256 digests over request bodies and rejecting mismatched payloads for existing keys with HTTP 409 Conflict.

**Independent Test**: Complete a transaction with `Idempotency-Key: pay_tamper_001` for $100. Submit a subsequent request with the same key for $500. Verify the system rejects it immediately with HTTP 409 Conflict and error code `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH` without calling downstream services.

### Tests for User Story 2 (TDD - Mandatory per Constitution Principle IV) ⚠️

- [ ] T017 [P] [US2] Write failing unit and integration tests for payload hashing and tampering detection in `src/test/java/io/deltaflow/idempotency/service/PayloadHasherTest.java` and `src/test/java/io/deltaflow/idempotency/integration/PayloadTamperingIntegrationTest.java`

### Implementation for User Story 2

- [ ] T018 [P] [US2] Implement SHA-256 payload digest generator in `src/main/java/io/deltaflow/idempotency/service/PayloadHasher.java` computing lowercase 64-character hexadecimal digests over raw request body bytes and query strings
- [ ] T019 [US2] Integrate hash verification in `IdempotencyCoordinator.java` in `src/main/java/io/deltaflow/idempotency/service/IdempotencyCoordinator.java`, rejecting mismatched digests with `PayloadMismatchException`
- [ ] T020 [US2] Implement global exception handler `IdempotencyExceptionHandler` in `src/main/java/io/deltaflow/idempotency/api/IdempotencyExceptionHandler.java` mapping `PayloadMismatchException` to HTTP 409 Conflict with code `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`
- [ ] T021 [US2] Verify and pass `PayloadTamperingIntegrationTest.java` in `src/test/java/io/deltaflow/idempotency/integration/PayloadTamperingIntegrationTest.java` proving altered request bodies with identical keys are rejected with HTTP 409

**Checkpoint**: User Stories 1 AND 2 work independently and in combination.

---

## Phase 5: User Story 3 - Concurrent Request Locking & In-Flight Collision Handling (Priority: P3)

**Goal**: Coordinate concurrent contenders hitting the same idempotency key simultaneously. Only one request acquires the distributed lock and processes downstream; contenders wait up to 3 seconds for resolution or return HTTP 409 Conflict (`IDEMPOTENCY_KEY_IN_FLIGHT`).

**Independent Test**: Blast 20 concurrent requests with the identical idempotency key and payload at the same millisecond. Exactly 1 request executes downstream logic, while the remaining 19 either unblock with the cached response or receive 409 Conflict.

### Tests for User Story 3 (TDD - Mandatory per Constitution Principle IV) ⚠️

- [ ] T022 [P] [US3] Write failing concurrency stress test in `src/test/java/io/deltaflow/idempotency/integration/DistributedLockConcurrencyTest.java` executing 20 concurrent threads for the same key

### Implementation for User Story 3

- [ ] T023 [P] [US3] Implement distributed lock interface `DistributedLockManager` in `src/main/java/io/deltaflow/idempotency/lock/DistributedLockManager.java` with acquire, release, and extend contracts
- [ ] T024 [US3] Implement Redis distributed lock provider `RedisDistributedLock` in `src/main/java/io/deltaflow/idempotency/lock/RedisDistributedLock.java` using Redisson RLock and Redis Pub/Sub transition notifications
- [ ] T025 [US3] Implement contender wait-and-poll loop in `IdempotencyCoordinator.java` in `src/main/java/io/deltaflow/idempotency/service/IdempotencyCoordinator.java` waiting up to 3 seconds for active transitions before returning HTTP 409 `IDEMPOTENCY_KEY_IN_FLIGHT`
- [ ] T026 [US3] Verify and pass `DistributedLockConcurrencyTest.java` in `src/test/java/io/deltaflow/idempotency/integration/DistributedLockConcurrencyTest.java` ensuring exactly one thread executes downstream and zero duplicate debits occur

**Checkpoint**: High-concurrency race conditions are safely locked across distributed threads.

---

## Phase 6: User Story 4 - Lease Timeout Expiration & Crash Recovery (Priority: P4)

**Goal**: Prevent orphaned locks and indefinite system hangs when an orchestrator node crashes or network partition occurs mid-flight by enforcing auto-expiring leases and safe lock reclamation.

**Independent Test**: Insert a record stuck in `PROCESSING` with `lease_expires_at` in the past. Send a retry request with that key. The system detects the expired lease, reclaims the lock, advances `lease_expires_at`, and processes successfully.

### Tests for User Story 4 (TDD - Mandatory per Constitution Principle IV) ⚠️

- [ ] T027 [P] [US4] Write failing integration test for lease expiration and lock reclamation in `src/test/java/io/deltaflow/idempotency/integration/LeaseTimeoutRecoveryIntegrationTest.java`

### Implementation for User Story 4

- [ ] T028 [P] [US4] Implement database lock fallback `PostgresLockFallback` in `src/main/java/io/deltaflow/idempotency/lock/PostgresLockFallback.java` using pessimistic row locking when Redis is unavailable
- [ ] T029 [US4] Implement lease expiration check and lock reclamation logic in `IdempotencyCoordinator.java` in `src/main/java/io/deltaflow/idempotency/service/IdempotencyCoordinator.java` allowing retries after `lease_expires_at` has elapsed
- [ ] T030 [US4] Verify and pass `LeaseTimeoutRecoveryIntegrationTest.java` in `src/test/java/io/deltaflow/idempotency/integration/LeaseTimeoutRecoveryIntegrationTest.java` proving expired locks in `Processing` can be safely reclaimed

**Checkpoint**: All 4 user stories are fully implemented, resilient, and independently validated.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Inspection tooling, OpenAPI documentation, and end-to-end quickstart validation

- [ ] T031 [P] Implement operator inspection endpoint `IdempotencyInspectorController` and DTOs in `src/main/java/io/deltaflow/idempotency/api/IdempotencyInspectorController.java` and `src/main/java/io/deltaflow/idempotency/api/dto/IdempotencyRecordDto.java` matching `/api/v1/idempotency/records/{key}`
- [ ] T032 [P] Configure Springdoc OpenAPI in `src/main/java/io/deltaflow/common/config/OpenApiConfig.java` matching `contracts/idempotency-api.yaml`
- [ ] T033 Execute end-to-end scenarios from `quickstart.md` in `src/test/java/io/deltaflow/idempotency/integration/QuickstartValidationTest.java`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately.
- **Foundational (Phase 2)**: Depends on Setup (Phase 1) - **BLOCKS all user stories**.
- **User Stories (Phases 3-6)**: Depend on Foundational (Phase 2) completion.
  - Can proceed sequentially in priority order: P1 (US1) $\rightarrow$ P2 (US2) $\rightarrow$ P3 (US3) $\rightarrow$ P4 (US4).
  - Or parallelized across team members once Foundational completes.
- **Polish (Phase 7)**: Depends on User Stories 1-4 completion.

### User Story Dependencies

- **User Story 1 (P1)**: Depends on Phase 2. Core MVP.
- **User Story 2 (P2)**: Depends on Phase 2 and hooks into `IdempotencyCoordinator` from US1.
- **User Story 3 (P3)**: Depends on Phase 2 and US1; adds distributed locking provider to `IdempotencyCoordinator`.
- **User Story 4 (P4)**: Depends on Phase 2, US1, and US3; adds lease expiration check and DB fallback to `IdempotencyCoordinator`.

### Parallel Opportunities

- **Setup Phase**: T002 and T003 can run in parallel.
- **Foundational Phase**: T005, T006, T007, T008, T009 can run in parallel after T004.
- **Within Each Story**: Failing test tasks and independent model/component tasks marked `[P]` can run concurrently.
- **Polish Phase**: T031 and T032 can run in parallel.

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001 - T003)
2. Complete Phase 2: Foundational (T004 - T010)
3. Complete Phase 3: User Story 1 (T011 - T016)
4. **STOP and VALIDATE**: Run `IdempotencyReplayIntegrationTest.java` to prove initial payment execution and replay deduplication with cached responses.

### Incremental Delivery

1. Setup + Foundational $\rightarrow$ Foundation Ready.
2. US1 $\rightarrow$ Core Replay Deduplication MVP.
3. US2 $\rightarrow$ Payload Tampering Protection (SHA-256 Digest).
4. US3 $\rightarrow$ Concurrency & Distributed Locking (Redis / Pub-Sub).
5. US4 $\rightarrow$ Lease Expiration & Fallback (Zombie Recovery).
6. Polish $\rightarrow$ Inspection API & Quickstart End-to-End Validation.

