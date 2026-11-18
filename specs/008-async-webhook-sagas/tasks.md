# Tasks: Event-Driven Messaging & Asynchronous Webhook Sagas

**Input**: Design documents from `/specs/008-async-webhook-sagas/` (`spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`)  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`  
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description with exact file path`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (`[US1]`, `[US2]`, `[US3]`, `[US4]`)
- Every task includes an exact file path and clear actionable criteria

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependency management and base messaging configuration

- [ ] T001 Add `org.springframework.kafka:spring-kafka` and Testcontainers Kafka/Redpanda dependencies in `pom.xml`
- [ ] T002 [P] Configure Kafka bootstrap servers, topics (`deltaflow.domain-events`, `deltaflow.domain-events.dlq`), partition count, and webhook secret properties in `src/main/resources/application.yml`
- [ ] T003 [P] Configure test profile overrides for dynamic Kafka Testcontainers bootstrap properties in `src/test/resources/application-test.yml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Database schema mutations and core domain models required by all user stories

**⚠️ CRITICAL**: Must complete before user story implementation can begin

- [ ] T004 Create database migration adding `awaiting_event_name`, `step_deadline` to `saga_instances` and `saga_steps`, `partition_key`, `error_details` to `outbox_events`, and creating `webhook_ingress_records` table in `src/main/resources/db/migration/V8__async_sagas_and_webhooks.sql`
- [ ] T005 [P] Update JPA entity mappings in `SagaInstance.java`, `SagaStep.java`, and `OutboxEvent.java` with new columns and status values in `src/main/java/io/deltaflow/saga/domain/` and `src/main/java/io/deltaflow/outbox/domain/`
- [ ] T006 [P] Extend Java 21 sealed interfaces `SagaState.java` and `SagaStepState.java` with `Suspended` and `TimedOut` records enforcing pattern-matching switch exhaustion in `src/main/java/io/deltaflow/saga/domain/`
- [ ] T007 [P] Create `WebhookIngressRecord.java` entity and `WebhookIngressRepository.java` interface in `src/main/java/io/deltaflow/saga/domain/` and `src/main/java/io/deltaflow/saga/repository/`
- [ ] T008 [P] Implement `KafkaBrokerConfig.java` configuring `ProducerFactory`, `KafkaTemplate`, and `NewTopic` beans with graceful degradation when Kafka is disabled in `src/main/java/io/deltaflow/config/KafkaBrokerConfig.java`

**Checkpoint**: Foundation ready — user story implementation can begin.

---

## Phase 3: User Story 1 - Pluggable Message Broker Outbox Dispatching (Priority: P1) 🎯 MVP

**Goal**: Stream domain events from the Transactional Outbox to partitioned Kafka topics with W3C `traceparent` headers and Dead-Letter Queue (DLQ) fault isolation.

**Independent Test**: Commit outbox records; run outbox publisher; verify events land on the target Kafka topic partitioned by `sagaId` with `traceparent` headers, and verify that poisoned events exceeding max retries move to the DLQ topic.

- [ ] T009 [P] [US1] Integration test verifying Kafka outbox message publishing, partition key assignment, and W3C `traceparent` header propagation in `src/test/java/io/deltaflow/outbox/integration/KafkaOutboxPublishingIntegrationTest.java`
- [ ] T010 [P] [US1] Integration test verifying Dead-Letter Queue (DLQ) routing and `DEAD_LETTER` database status upon retry exhaustion in `src/test/java/io/deltaflow/outbox/integration/KafkaOutboxDeadLetterIntegrationTest.java`
- [ ] T011 [US1] Implement `KafkaDomainEventPublisher.java` implementing `DomainEventPublisher` with partitioning by `aggregateId` and OpenTelemetry header injection in `src/main/java/io/deltaflow/outbox/service/KafkaDomainEventPublisher.java`
- [ ] T012 [US1] Update `OutboxPublisher.java` to route poisoned events to the DLQ topic and record error details in PostgreSQL when retry count exceeds threshold in `src/main/java/io/deltaflow/outbox/service/OutboxPublisher.java`
- [ ] T013 [US1] Update `docker-compose.yml` to include a lightweight Redpanda message broker service on port 9092 with health checks in `docker-compose.yml`

**Checkpoint**: User Story 1 complete — outbox events reliably stream to partitioned Kafka topics with DLQ protection.

---

## Phase 4: User Story 2 - Declarative Suspended Sagas (`WAITING_FOR_EVENT`) & Resource Yielding (Priority: P1) 🎯 MVP

**Goal**: Allow sagas to declare asynchronous waiting steps, transitioning to `SUSPENDED` and relinquishing virtual threads and distributed lock leases.

**Independent Test**: Execute a saga with an asynchronous step; assert that the saga transitions to `SUSPENDED`, records the deadline, releases the Redis distributed lock, and exits the virtual thread without blocking carrier threads.

- [ ] T014 [P] [US2] Integration test verifying saga enters `SUSPENDED` status, releases virtual thread, and relinquishes Redis distributed lock in `src/test/java/io/deltaflow/saga/integration/SagaAsyncSuspensionIntegrationTest.java`
- [ ] T015 [US2] Extend `SagaStepDefinition.java` and `SagaBuilder.java` with `asyncWait(String name, String awaitingEventName, Duration timeout, BiConsumer compensation)` DSL in `src/main/java/io/deltaflow/saga/builder/`
- [ ] T016 [US2] Update `SagaCoordinator.java` to detect `isAsyncWait()`, record `SUSPENDED` step and instance state in journal store, release distributed lock, and yield virtual thread in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`
- [ ] T017 [US2] Update `SagaJournalStore.java` to persist `SUSPENDED` state with `awaiting_event_name` and `step_deadline` in `src/main/java/io/deltaflow/saga/service/SagaJournalStore.java`

**Checkpoint**: User Story 2 complete — long-running sagas suspend cleanly and yield execution threads.

---

## Phase 5: User Story 3 - Cryptographic Webhook Ingress & Safe Saga Resumption (Priority: P2)

**Goal**: Secure webhook ingress verifying HMAC-SHA256 signatures, deduplicating callbacks via the Idempotency Gate, re-acquiring locks, and resuming saga forward execution.

**Independent Test**: Deliver valid signed webhook to `POST /api/v1/sagas/{sagaId}/events/{eventName}`; verify signature passes, lock is re-acquired, step is marked completed, and subsequent forward steps execute to completion.

- [ ] T018 [P] [US3] Integration test verifying HMAC-SHA256 signature verification, anti-replay timestamp enforcement, and forged signature rejection in `src/test/java/io/deltaflow/saga/integration/SagaWebhookSecurityIntegrationTest.java`
- [ ] T019 [P] [US3] Integration test verifying suspended saga resumption upon valid webhook receipt, lock re-acquisition, fencing token check, and forward step completion in `src/test/java/io/deltaflow/saga/integration/SagaWebhookResumptionIntegrationTest.java`
- [ ] T020 [US3] Implement `WebhookSecurityService.java` verifying HMAC-SHA256 signatures with constant-time comparison and anti-replay timestamp validation ($\le 300$s) in `src/main/java/io/deltaflow/saga/webhook/WebhookSecurityService.java`
- [ ] T021 [US3] Implement `WebhookIngressController.java` exposing `POST /api/v1/sagas/{sagaId}/events/{eventName}` guarded by `IdempotencyCoordinator` and `WebhookSecurityService` in `src/main/java/io/deltaflow/saga/webhook/WebhookIngressController.java`
- [ ] T022 [US3] Implement `resumeSaga` method in `SagaCoordinator.java` to re-acquire distributed lock, validate fencing token, mark suspended step completed with webhook payload, and execute remaining forward steps in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`

**Checkpoint**: User Story 3 complete — external webhooks securely authenticate and resume suspended sagas.

---

## Phase 6: User Story 4 - Asynchronous Timeout Expiration & Automatic Backward Compensation (Priority: P2)

**Goal**: Automatically detect expired suspended sagas whose callbacks did not arrive within deadline, triggering reverse LIFO compensation across all completed steps.

**Independent Test**: Initiate an async saga with a short timeout; allow deadline to pass; verify scanner transitions saga to `TIMED_OUT` and backward compensation refunds preceding steps.

- [ ] T023 [P] [US4] Integration test verifying expired suspended saga transitions to `TIMED_OUT` and executes backward compensation in LIFO order in `src/test/java/io/deltaflow/saga/integration/SagaAsyncTimeoutCompensationIntegrationTest.java`
- [ ] T024 [P] [US4] Integration test verifying late-arriving zombie webhooks for timed-out/compensated sagas are rejected with HTTP 409 Conflict in `src/test/java/io/deltaflow/saga/integration/SagaZombieWebhookRejectionIntegrationTest.java`
- [ ] T025 [US4] Add `findExpiredSuspendedForUpdate` query to `SagaInstanceRepository.java` to locate expired suspended sagas using `PESSIMISTIC_WRITE` in `src/main/java/io/deltaflow/saga/repository/SagaInstanceRepository.java`
- [ ] T026 [US4] Extend `SagaRecoveryScanner.java` to scan expired suspended sagas, acquire locks, transition to `TIMED_OUT`, and invoke `compensateSaga` in `src/main/java/io/deltaflow/saga/service/SagaRecoveryScanner.java`
- [ ] T027 [US4] Update `WebhookIngressController.java` to reject callbacks with HTTP 409 Conflict when saga instance is in terminal states (`TIMED_OUT`, `COMPENSATING`, `COMPENSATED`, `FAILED`) in `src/main/java/io/deltaflow/saga/webhook/WebhookIngressController.java`

**Checkpoint**: User Story 4 complete — timeouts trigger guaranteed LIFO compensation with zombie callback rejection.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: System documentation, operator visibility, and end-to-end regression testing

- [ ] T028 [P] Update Operator Dashboard DTOs (`SagaSummaryDto`, `SagaNodeDto`) and event broadcaster to stream `SAGA_SUSPENDED`, `SAGA_RESUMED`, and `SAGA_TIMED_OUT` events in `src/main/java/io/deltaflow/dashboard/`
- [ ] T029 [P] Update `README.md` and `quickstart.md` with asynchronous webhook saga documentation, Kafka configuration options, and curl testing instructions in `README.md`
- [ ] T030 Run full regression test suite `mvn test -B` with Testcontainers (PostgreSQL, Redis, Kafka/Redpanda) verifying 100% pass rate across all unit, integration, and chaos tests

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Phase 1 completion — BLOCKS all user stories.
- **User Story 1 (Phase 3 - MVP)**: Depends on Foundational completion.
- **User Story 2 (Phase 4 - MVP)**: Depends on Foundational completion.
- **User Story 3 (Phase 5)**: Depends on US2 completion (resumes suspended sagas).
- **User Story 4 (Phase 6)**: Depends on US2 completion (times out suspended sagas).
- **Polish (Phase 7)**: Depends on all user stories being complete.

### Parallel Opportunities
- Within Phase 1: `T002` and `T003` can run in parallel.
- Within Phase 2: `T005`, `T006`, `T007`, and `T008` can run in parallel.
- User Story 1 (`T009`, `T010`) tests can run in parallel.
- User Story 2 (`T014`), User Story 3 (`T018`, `T019`), and User Story 4 (`T023`, `T024`) tests can be authored in parallel.
- Phase 7: `T028` and `T029` can run in parallel before final test suite execution (`T030`).

---

## Implementation Strategy

### MVP First (User Stories 1 & 2)
1. Complete Phase 1: Setup (`pom.xml`, `application.yml`).
2. Complete Phase 2: Foundational (migration, sealed records, entities).
3. Complete Phase 3: User Story 1 (Kafka publisher, outbox partition dispatch, DLQ).
4. Complete Phase 4: User Story 2 (asyncWait DSL, thread yielding, lock release).
5. **Validate MVP**: Test outbox streaming to Kafka topic and verify a saga suspends and releases its lock.

### Incremental Delivery
1. Add User Story 3: Implement HMAC-SHA256 webhook ingress and saga resumption.
2. Add User Story 4: Implement timeout scanner and automatic LIFO backward compensation.
3. Final Polish: Update dashboard DTOs, README, and verify 100% test pass rate across the full suite.
