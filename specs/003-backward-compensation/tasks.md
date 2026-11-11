# Tasks: Backward Compensation, Crash Recovery & Zombie Fencing

**Input**: Design documents from `/specs/003-backward-compensation/`  
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)  
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.  

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (`[US1]`, `[US2]`, `[US3]`, `[US4]`)
- Exact file paths included in all descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Database migration and foundational entity model extensions

- [x] T001 Create Flyway migration script adding fencing_token and indexes in `src/main/resources/db/migration/V3__add_fencing_token_to_saga_steps.sql`
- [x] T002 [P] Update `SagaStep.java` entity to map `fencing_token` with getter/setter in `src/main/java/io/deltaflow/saga/domain/SagaStep.java`
- [x] T003 [P] Extend `SagaState.java` and `SagaStepState.java` sealed interfaces and record patterns with `Compensated` and `Compensating` in `src/main/java/io/deltaflow/saga/domain/SagaState.java` and `src/main/java/io/deltaflow/saga/domain/SagaStepState.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core state transition dispatchers and repository queries required by all stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Update `SagaCoordinator.java` state machine switch dispatchers with exhaustive pattern-matching for `Compensating` and `Compensated` states in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`
- [x] T005 [P] Extend `SagaJournalStore.java` with WAL transaction methods for step compensation (`recordStepCompensating`, `recordStepCompensated`, `startSagaCompensation`, `completeSagaCompensation`) and audit logging in `src/main/java/io/deltaflow/saga/service/SagaJournalStore.java`
- [x] T006 [P] Add query for expired in-flight sagas using pessimistic locking (`findExpiredInFlightForUpdate`) in `src/main/java/io/deltaflow/saga/repository/SagaInstanceRepository.java`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Automatic Backward Compensation on Step Failure (Priority: P1) 🎯 MVP

**Goal**: When a downstream step fails, DeltaFlow halts forward execution, transitions the saga to `Compensating`, and invokes registered compensation handlers for prior completed steps in strict reverse order (e.g. credit failure refunds debit), settling the saga as `Compensated`.

**Independent Test**: `mvn test -Dtest=SagaBackwardCompensationIntegrationTest` verifies that an injected credit failure triggers debit refund, updates all journal records, and returns HTTP 422 with compensation details.

### Tests for User Story 1 ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T007 [P] [US1] Integration test verifying automatic backward compensation and debit refund on credit failure in `src/test/java/io/deltaflow/saga/integration/SagaBackwardCompensationIntegrationTest.java`

### Implementation for User Story 1

- [x] T008 [P] [US1] Implement refund/compensation methods in demo services (`DebitService.refundDebit`, `CreditService.reverseCredit`, `LedgerService.voidJournalEntry`) in `src/main/java/io/deltaflow/saga/demo/service/DebitService.java`, `src/main/java/io/deltaflow/saga/demo/service/CreditService.java`, and `src/main/java/io/deltaflow/saga/demo/service/LedgerService.java`
- [x] T009 [US1] Wire compensation handlers into demo pipeline definition in `src/main/java/io/deltaflow/saga/demo/TransferSagaConfig.java`
- [x] T010 [US1] Implement reverse rollback loop in `SagaCoordinator.compensateSaga` executing completed steps from `stepIndex - 1` down to 0 with WAL persistence in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`
- [x] T011 [US1] Update `TransferController.java` to return HTTP 422 with structured compensation response when saga completes compensation in `src/main/java/io/deltaflow/saga/api/TransferController.java`
- [x] T012 [US1] Update `IdempotencyCoordinator` terminal record persistence on compensation so client retries receive cached 422 response in `src/main/java/io/deltaflow/saga/api/TransferController.java`

**Checkpoint**: User Story 1 is fully functional and independently verified (MVP).

---

## Phase 4: User Story 2 - Startup & Periodic Crash Recovery Scanner (Priority: P1)

**Goal**: Autonomous background daemon scanning PostgreSQL for expired execution leases on boot and every 5 seconds, acquiring orphaned sagas using `FOR UPDATE SKIP LOCKED` and resuming forward execution or executing backward compensation.

**Independent Test**: `mvn test -Dtest=SagaCrashRecoveryIntegrationTest` inserts an orphaned saga in `EXECUTING` with an expired lease, triggers the recovery scanner, and verifies that the scanner claims the lease and recovers the saga to `COMPENSATED`.

### Tests for User Story 2 ⚠️

- [x] T013 [P] [US2] Integration test verifying crash recovery scanner claims expired leases and settles orphaned sagas in `src/test/java/io/deltaflow/saga/integration/SagaCrashRecoveryIntegrationTest.java`

### Implementation for User Story 2

- [x] T014 [US2] Implement `SagaRecoveryScanner` scheduled daemon with `@Scheduled` and `ApplicationReadyEvent` listener in `src/main/java/io/deltaflow/saga/service/SagaRecoveryScanner.java`
- [x] T015 [US2] Implement recovery logic claiming expired instances via `SagaInstanceRepository.findExpiredInFlightForUpdate` and delegating to `SagaCoordinator.recoverSaga` in `src/main/java/io/deltaflow/saga/service/SagaRecoveryScanner.java`
- [x] T016 [US2] Emit structured audit log events `RECOVERY_CLAIMED` and `RECOVERY_COMPLETED` with recovery worker ID in `src/main/java/io/deltaflow/saga/service/SagaRecoveryScanner.java`

**Checkpoint**: User Stories 1 AND 2 both work independently and handle both online failure and offline crash scenarios.

---

## Phase 5: User Story 3 - Monotonic Fencing Tokens & Zombie Step Protection (Priority: P2)

**Goal**: Attach monotonic fencing tokens to step dispatches and reject late-arriving responses or operations from slow/partitioned zombie steps arriving after compensation has initiated or completed.

**Independent Test**: `mvn test -Dtest=SagaZombieFencingIntegrationTest` simulates a late completion attempt with a stale fencing token against a compensating saga and verifies rejection with `ZOMBIE_STEP_REJECTED`.

### Tests for User Story 3 ⚠️

- [x] T017 [P] [US3] Integration test verifying stale fencing token rejection and zombie protection in `src/test/java/io/deltaflow/saga/integration/SagaZombieFencingIntegrationTest.java`

### Implementation for User Story 3

- [x] T018 [US3] Implement monotonic token increment and validation logic in `SagaJournalStore` and `SagaCoordinator` in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`
- [x] T019 [US3] Add validation rejecting state updates if current step status is `COMPENSATING` or `COMPENSATED` and emit `ZOMBIE_STEP_REJECTED` audit event in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`

**Checkpoint**: Sagas are zombie-proofed against delayed responses.

---

## Phase 6: User Story 4 - Declarative Compensation Registration & Context Delivery (Priority: P3)

**Goal**: Ensure `SagaBuilder` fluently registers compensation actions alongside forward actions and passes forward execution context into compensation handlers.

**Independent Test**: `mvn test -Dtest=SagaBuilderCompensationTest` verifies builder compiles, registers compensation handlers, and context receives outputs from prior steps.

### Tests for User Story 4 ⚠️

- [x] T020 [P] [US4] Unit tests for declarative compensation registration in `src/test/java/io/deltaflow/saga/builder/SagaBuilderCompensationTest.java`

### Implementation for User Story 4

- [x] T021 [US4] Verify and ensure `SagaBuilder` propagates `SagaExecutionContext` with forward step results into `compensation.accept(context, input)` in `src/main/java/io/deltaflow/saga/builder/SagaBuilder.java`

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: System inspection endpoints and end-to-end regression verification

- [x] T022 [P] Update `SagaInspectorController.java` to expose step fencing tokens and compensated status in `src/main/java/io/deltaflow/saga/api/SagaInspectorController.java`
- [x] T023 Run full quickstart validation scenarios from `specs/003-backward-compensation/quickstart.md`
- [x] T024 Run full test suite `mvn test -B` with Testcontainers verifying all 16 existing tests plus new Milestone 3 tests pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Phase 2 completion
  - User Story 1 (P1): MVP core rollback execution
  - User Story 2 (P1): Extends US1 rollback to crash recovery scanning
  - User Story 3 (P2): Fencing token validation guards US1/US2 rollbacks
  - User Story 4 (P3): Fluent API refinement
- **Polish (Phase 7)**: Depends on all user stories completing

---

## Parallel Opportunities

- **Phase 1**: T002 and T003 can execute in parallel.
- **Phase 2**: T005 and T006 can execute in parallel.
- **User Story 1**: T007 (test) and T008 (demo service refund methods) can be authored in parallel.
- **User Story 2**: T013 (test) can run while T014 is being scaffolded.
- **User Story 3**: T017 (test) can be created in parallel with token persistence.
- **User Story 4**: T020 (test) runs independently.

---

## Implementation Strategy

### MVP First (User Story 1)
1. Complete Phase 1 (Schema & Entities)
2. Complete Phase 2 (Foundational state transitions & WAL store methods)
3. Complete Phase 3 (User Story 1: Reverse compensation on step failure)
4. Validate with `SagaBackwardCompensationIntegrationTest` (Delivers MVP rollback capability!)

### Incremental Delivery
1. Add Phase 4 (User Story 2: Crash recovery scanner) -> Recovers from JVM crashes
2. Add Phase 5 (User Story 3: Fencing tokens) -> Zombie proofing
3. Add Phase 6 (User Story 4: Builder verification) -> Developer ergonomics
4. Complete Phase 7 (Polish & full test verification)

