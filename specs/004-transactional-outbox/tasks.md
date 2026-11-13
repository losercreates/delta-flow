# Tasks: Transactional Outbox & Resilience Policies

**Input**: Design documents from `/specs/004-transactional-outbox/`  
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)  
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.  

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (`[US1]`, `[US2]`, `[US3]`, `[US4]`)
- Exact file paths included in all descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependency management and relational schema migrations

- [x] T001 Add `resilience4j-spring-boot3` (version 2.2.0) dependency to `pom.xml`
- [x] T002 [P] Create Flyway migration script adding `outbox_events` table and indexes in `src/main/resources/db/migration/V4__create_outbox_events_table.sql`
- [x] T003 [P] Create `OutboxStatus.java` enum (`PENDING`, `IN_FLIGHT`, `PUBLISHED`, `DEAD_LETTER`) in `src/main/java/io/deltaflow/outbox/domain/OutboxStatus.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core entity model, repository queries, and event publisher interfaces required by all stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Create `OutboxEvent.java` entity mapping table `outbox_events` with columns `event_id UUID`, `aggregate_type VARCHAR(64) NOT NULL`, `aggregate_id VARCHAR(128) NOT NULL`, `event_type VARCHAR(64) NOT NULL`, `payload JSONB NOT NULL`, `status VARCHAR(32) NOT NULL DEFAULT 'PENDING'`, `retry_count INT NOT NULL DEFAULT 0`, `error_details TEXT`, `created_at TIMESTAMPTZ NOT NULL`, and `published_at TIMESTAMPTZ` in `src/main/java/io/deltaflow/outbox/domain/OutboxEvent.java`
- [x] T005 [P] Create `OutboxEventRepository.java` extending `JpaRepository` with pessimistic write lock query `findPendingForUpdate(limit)` using `-2` timeout hint (`SKIP LOCKED`) ordered by `created_at ASC` in `src/main/java/io/deltaflow/outbox/repository/OutboxEventRepository.java`
- [x] T006 [P] Create `DomainEventPublisher.java` interface defining `void publish(OutboxEvent event)` and a default in-memory publisher bean in `src/main/java/io/deltaflow/outbox/service/DomainEventPublisher.java`
- [x] T007 Create transactional `OutboxService.java` providing `recordEvent(String aggregateType, String aggregateId, String eventType, Object payload)` in `src/main/java/io/deltaflow/outbox/service/OutboxService.java`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Transactional Outbox for Dual-Write-Free Event Publishing (Priority: P1) 🎯 MVP

**Goal**: Atomically persist domain events to PostgreSQL within the saga WAL transaction and asynchronously publish them via an autonomous scheduled relay daemon with guaranteed at-least-once delivery.

**Independent Test**: `mvn test -Dtest=OutboxPublishingIntegrationTest` verifies that a money transfer transaction atomically inserts outbox records, the outbox relay claims and publishes them, and event status transitions to `PUBLISHED` with timestamps.

### Tests for User Story 1 ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T008 [P] [US1] Integration test verifying atomic outbox persistence during saga execution and relay dispatch in `src/test/java/io/deltaflow/outbox/integration/OutboxPublishingIntegrationTest.java`

### Implementation for User Story 1

- [x] T009 [US1] Implement `OutboxPublisher.java` scheduled daemon polling pending events with `findPendingForUpdate`, dispatching via `DomainEventPublisher`, and transitioning status to `PUBLISHED` with `published_at` timestamp in `src/main/java/io/deltaflow/outbox/service/OutboxPublisher.java`
- [x] T010 [US1] Integrate `OutboxService` into `SagaCoordinator.java` to atomically record domain events (`SAGA_STARTED`, `STEP_COMPLETED`, `SAGA_COMPLETED`, `SAGA_COMPENSATED`) in the same database transaction as the saga WAL writes in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`
- [x] T011 [US1] Emit structured audit events `OUTBOX_RECORDED` and `OUTBOX_PUBLISHED` with correlation IDs and timestamps in `src/main/java/io/deltaflow/outbox/service/OutboxPublisher.java`

**Checkpoint**: User Story 1 is fully functional and independently verified (MVP delivered).

---

## Phase 4: User Story 2 - Circuit Breakers & Bounded Retries for External Dependencies (Priority: P1)

**Goal**: Wrap external step calls in Resilience4j circuit breakers and exponential backoff retries with jitter to absorb transient network glitches and fail fast into backward compensation when dependencies are down.

**Independent Test**: `mvn test -Dtest=SagaResilienceIntegrationTest` verifies that transient 5xx errors retry up to 3 times before failing, and sustained errors trip the circuit to `OPEN`, immediately fast-failing subsequent calls with `CallNotPermittedException` and triggering saga compensation.

### Tests for User Story 2 ⚠️

- [x] T012 [P] [US2] Integration test verifying exponential backoff retries and circuit breaker transitions from `CLOSED` to `OPEN` in `src/test/java/io/deltaflow/resilience/integration/SagaResilienceIntegrationTest.java`

### Implementation for User Story 2

- [x] T013 [P] [US2] Create `ResilienceConfig.java` configuring `CircuitBreakerRegistry` and `RetryRegistry` with sliding window size (10), failure rate threshold (50%), wait duration in open (5s), and exponential backoff retry in `src/main/java/io/deltaflow/resilience/ResilienceConfig.java`
- [x] T014 [US2] Create `ResiliencePolicyRegistry.java` providing programmatic decoration methods for functional actions (`decorateSupplier`) in `src/main/java/io/deltaflow/resilience/ResiliencePolicyRegistry.java`
- [x] T015 [US2] Extend `SagaBuilder.java` and `SagaStepDefinition.java` to support `.withCircuitBreaker(String name)` and `.withRetry(String name)` on steps in `src/main/java/io/deltaflow/saga/builder/SagaBuilder.java`
- [x] T016 [US2] Update `SagaCoordinator.java` to execute step actions through registered resilience decorators, catching `CallNotPermittedException` to fail fast and trigger backward compensation in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`

**Checkpoint**: User Stories 1 AND 2 both work independently.

---

## Phase 5: User Story 3 - Coordinated Parallel Branches with Java 21 Structured Concurrency (Priority: P2)

**Goal**: Coordinate concurrent subtasks (e.g. customer notification and analytical recording) using Java 21 `StructuredTaskScope` and Virtual Threads with automatic cancellation propagation (`ShutdownOnFailure`) and zero thread leaks.

**Independent Test**: `mvn test -Dtest=SagaStructuredConcurrencyIntegrationTest` verifies that parallel branch subtasks run concurrently on Virtual Threads, aggregate outputs, and automatically cancel sibling branches when a failure occurs.

### Tests for User Story 3 ⚠️

- [x] T017 [P] [US3] Integration test verifying parallel branch execution and automatic sibling cancellation on failure in `src/test/java/io/deltaflow/saga/integration/SagaStructuredConcurrencyIntegrationTest.java`

### Implementation for User Story 3

- [x] T018 [P] [US3] Create `ParallelBranchDefinition.java` representing named branch actions and optional compensations in `src/main/java/io/deltaflow/saga/builder/ParallelBranchDefinition.java`
- [x] T019 [US3] Extend `SagaBuilder.java` with `.parallelStep(String stepName, Consumer<ParallelStepBuilder<I>> configurer)` in `src/main/java/io/deltaflow/saga/builder/SagaBuilder.java`
- [x] T020 [US3] Implement parallel branch execution in `SagaCoordinator.java` using Virtual Threads and `StructuredTaskScope.ShutdownOnFailure` to join subtasks, cancel on failure, and aggregate results in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`

**Checkpoint**: Parallel branches execute safely with strict structured lifecycle boundaries.

---

## Phase 6: User Story 4 - Outbox Poison Pill Quarantine & Dead Letter Handling (Priority: P3)

**Goal**: Quarantine persistently failing outbox events exceeding maximum retry attempts into a `DEAD_LETTER` state without blocking subsequent healthy outbox messages.

**Independent Test**: `mvn test -Dtest=OutboxDeadLetterIntegrationTest` verifies that an unpublishable poison pill event transitions to `DEAD_LETTER` after 5 failed attempts, emits `OUTBOX_DEAD_LETTER`, and does not block subsequent valid events.

### Tests for User Story 4 ⚠️

- [x] T021 [P] [US4] Integration test verifying that unpublishable poison pill events transition to `DEAD_LETTER` after retry exhaustion without blocking other events in `src/test/java/io/deltaflow/outbox/integration/OutboxDeadLetterIntegrationTest.java`

### Implementation for User Story 4

- [x] T022 [US4] Add retry increment and poison pill quarantine logic to `OutboxPublisher.java`, transitioning events to `DEAD_LETTER` when `retry_count >= max_retries` (default: 5) and recording error stack traces in `src/main/java/io/deltaflow/outbox/service/OutboxPublisher.java`
- [x] T023 [US4] Emit structured audit event `OUTBOX_DEAD_LETTER` with event ID, aggregate ID, and error details in `src/main/java/io/deltaflow/outbox/service/OutboxPublisher.java`

**Checkpoint**: Outbox pipeline is immune to head-of-line blocking by poisoned events.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Outbox REST inspection endpoints and end-to-end regression verification

- [x] T024 [P] Create `OutboxEventDto.java` and `OutboxInspectorController.java` implementing `GET /api/v1/outbox` and `GET /api/v1/outbox/{eventId}` in `src/main/java/io/deltaflow/outbox/api/OutboxInspectorController.java` and `src/main/java/io/deltaflow/outbox/api/dto/OutboxEventDto.java`
- [x] T025 Run quickstart validation scenarios from `specs/004-transactional-outbox/quickstart.md`
- [x] T026 Run full test suite `mvn test -B` with Testcontainers verifying all 22 existing tests plus new Milestone 4 tests pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Phase 2 completion
  - User Story 1 (P1): Core Transactional Outbox atomic write & relay publishing (MVP)
  - User Story 2 (P1): Resilience4j circuit breakers & retries on external steps
  - User Story 3 (P2): Structured Concurrency for parallel branch steps
  - User Story 4 (P3): Dead Letter quarantine for outbox poison pills
- **Polish (Phase 7)**: Depends on all user stories being complete

---

## Parallel Opportunities

- **Phase 1**: T002 and T003 can execute in parallel.
- **Phase 2**: T005 and T006 can execute in parallel.
- **Phase 3 (US1)**: T008 (test) can run while T009 is being implemented.
- **Phase 4 (US2)**: T012 (test) and T013 (config) can run in parallel.
- **Phase 5 (US3)**: T017 (test) and T018 (branch model) can run in parallel.
- **Phase 6 (US4)**: T021 (test) can run while T022 is being implemented.
- **Phase 7**: T024 (inspector controller) can be implemented in parallel with test verification.

---

## Implementation Strategy

### MVP First (User Story 1)
1. Complete Phase 1: Setup (`pom.xml` dependencies, Flyway `V4`, `OutboxStatus`)
2. Complete Phase 2: Foundational (`OutboxEvent`, `OutboxEventRepository`, `OutboxService`)
3. Complete Phase 3: User Story 1 (`OutboxPublishingIntegrationTest`, `OutboxPublisher`, WAL integration)
4. Validate with `mvn test -Dtest=OutboxPublishingIntegrationTest` (Delivers MVP Outbox capability!)

### Incremental Delivery
1. Add Phase 4 (User Story 2: Resilience Policies) -> Circuit breakers & retries
2. Add Phase 5 (User Story 3: Structured Concurrency) -> Parallel branch execution
3. Add Phase 6 (User Story 4: Dead Letter Handling) -> Poison pill resilience
4. Polish (Phase 7: Inspector Controller & Full Regression Suite)

