# Tasks: High Availability, Distributed Leader Coordination & Storage Tiering

**Input**: Design documents from `specs/010-clustering-storage-tiering/` (`plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/cluster-ha-api.yaml`, `quickstart.md`)  
**Prerequisites**: `plan.md`, `spec.md`, `data-model.md`, `contracts/`  
**Constitution Alignment**: Principle I (Zero Double-Spend), Principle II (Strict LIFO Compensation), Principle III (Java 21 Type Safety), Principle IV (TDD & Chaos Verification via Testcontainers), Principle V (Immutable Audit Trail).  

## Format: `- [ ] [TaskID] [P?] [Story?] Description with file path`

- **[P]**: Can run in parallel (independent files and dependencies)
- **[Story]**: User Story label ([US1], [US2], [US3], [US4])

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish database schema migrations, partial indexes, and shared DTOs across backend and frontend.

- [ ] T001 [P] Create Flyway migration `V6__outbox_lease_and_clustering.sql` in `src/main/resources/db/migration/V6__outbox_lease_and_clustering.sql` adding `lease_expires_at` and `claimed_by_worker` columns to `outbox_events` and partial indexes
- [ ] T002 [P] Create cluster and retention DTO records (`ClusterNodeDto.java`, `LeaderStatusDto.java`, `RetentionSummaryDto.java`, `RetentionTriggerRequest.java`, `OutboxLeaseReclaimResult.java`) in `src/main/java/io/deltaflow/cluster/domain/`
- [ ] T003 [P] Create frontend TypeScript definitions in `frontend/src/types/cluster.ts`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core entity and repository query extensions required before user stories can execute.

- [ ] T004 Update `OutboxEvent` entity in `src/main/java/io/deltaflow/outbox/domain/OutboxEvent.java` with `leaseExpiresAt` and `claimedByWorker` fields and helper methods
- [ ] T005 [P] Update `OutboxEventRepository` in `src/main/java/io/deltaflow/outbox/repository/OutboxEventRepository.java` with `findExpiredInFlightForUpdate` query using `PESSIMISTIC_WRITE`
- [ ] T006 Update `IdempotencyRecordRepository` in `src/main/java/io/deltaflow/idempotency/repository/IdempotencyRecordRepository.java` with `findExpiredResolvedRecords` and batch delete queries

**Checkpoint**: Foundational schema and repositories ready — user story implementation can begin.

---

## Phase 3: User Story 1 - In-Flight Outbox Lease Recovery & Worker Crash Healing (Priority: P1) 🎯 MVP

**Goal**: Automatically detect outbox events stranded in `IN_FLIGHT` status past their lease expiration due to worker crashes and reset them to `PENDING` with retry counter increments and audit logging.

**Independent Test**: Seed an in-flight outbox event with an expired lease timestamp; run the recovery reaper; verify that the event transitions back to `PENDING`, increments its retry count, logs an `OUTBOX_LEASE_RECLAIMED` audit event, and dispatches on the next sweep.

### Tests for User Story 1 ⚠️
- [ ] T007 [P] [US1] Create integration test `InFlightOutboxLeaseRecoveryIntegrationTest.java` in `src/test/java/io/deltaflow/cluster/InFlightOutboxLeaseRecoveryIntegrationTest.java` verifying lease stamping, recovery of expired in-flight events, and routing to dead-letter queue after max retries

### Implementation for User Story 1
- [ ] T008 [US1] Update `OutboxPublisher.java` in `src/main/java/io/deltaflow/outbox/service/OutboxPublisher.java` to stamp `leaseExpiresAt` (now + 60s) and `claimedByWorker` when claiming `PENDING` events
- [ ] T009 [US1] Implement `OutboxLeaseRecoveryReaper.java` in `src/main/java/io/deltaflow/outbox/service/OutboxLeaseRecoveryReaper.java` with periodic scanning to reclaim expired `IN_FLIGHT` events and broadcast `OUTBOX_LEASE_RECLAIMED` live events

**Checkpoint**: User Story 1 is fully functional and testable independently (MVP ready).

---

## Phase 4: User Story 2 - Distributed Leader Coordination & Single-Active Scheduler Clustering (Priority: P1) 🎯 MVP

**Goal**: Coordinate background workers (`SagaRecoveryScanner`, `OutboxPublisher`, retention cleaners) via distributed leader election using Redis/Redisson, ensuring single-active execution with automatic failover under 5 seconds.

**Independent Test**: Run two cluster nodes; verify that exactly one node acquires the coordinator leadership lease; verify that background tasks run only on the leader; terminate the leader and verify that the standby node assumes leadership within 5 seconds and resumes scanning.

### Tests for User Story 2 ⚠️
- [ ] T010 [P] [US2] Create integration test `ClusterLeaderElectionIntegrationTest.java` in `src/test/java/io/deltaflow/cluster/ClusterLeaderElectionIntegrationTest.java` validating leader lease acquisition, periodic renewal, standby node contention, and automated failover

### Implementation for User Story 2
- [ ] T011 [US2] Implement `ClusterLeaderElectionService.java` in `src/main/java/io/deltaflow/cluster/service/ClusterLeaderElectionService.java` using `RedissonClient` for atomic lease acquisition (5s), scheduled heartbeat renewal (1.5s), and node topology registration
- [ ] T012 [US2] Wire `SagaRecoveryScanner.java` in `src/main/java/io/deltaflow/saga/service/SagaRecoveryScanner.java` and `OutboxPublisher.java` to check `ClusterLeaderElectionService.isLeader()` before executing scheduled background cycles

**Checkpoint**: User Story 2 is fully functional and testable independently.

---

## Phase 5: User Story 3 - Automated Idempotency Retention Pruning & Data Lifecycle Tiering (Priority: P2)

**Goal**: Implement a scheduled data retention manager that prunes terminal `RESOLVED` idempotency records older than the SLA window (default 30 days) in bounded transactional batches while strictly preserving active locks.

**Independent Test**: Seed resolved idempotency records older than 30 days alongside active `PROCESSING` records; execute the retention cleaner; verify that only expired resolved records are pruned, active locks remain 100% intact, and a summary audit log is emitted.

### Tests for User Story 3 ⚠️
- [ ] T013 [P] [US3] Create integration test `IdempotencyRetentionIntegrationTest.java` in `src/test/java/io/deltaflow/cluster/IdempotencyRetentionIntegrationTest.java` verifying batch deletion of expired `RESOLVED` records and 100% preservation of active `PROCESSING` leases

### Implementation for User Story 3
- [ ] T014 [US3] Implement `IdempotencyRetentionService.java` in `src/main/java/io/deltaflow/cluster/service/IdempotencyRetentionService.java` executing scheduled bounded batch deletion (default 500 rows) with audit logging and SSE event broadcasting
- [ ] T015 [US3] Wire leader election check in `IdempotencyRetentionService.java` so that retention pruning runs exclusively on the elected cluster leader

**Checkpoint**: User Story 3 is fully functional and testable independently.

---

## Phase 6: User Story 4 - Cluster Topology Telemetry & High-Availability Dashboard Visibility (Priority: P2)

**Goal**: Expose cluster node metadata, leader election status, and retention statistics via REST endpoints and live SSE streaming to the Operator Dashboard.

**Independent Test**: Query `/api/v1/cluster/nodes` and `/api/v1/cluster/leader`; verify that all active nodes appear with roles and heartbeat age, and verify that triggering retention or lease reclamation updates dashboard telemetry.

### Implementation for User Story 4
- [ ] T016 [US4] Implement `ClusterApiController.java` in `src/main/java/io/deltaflow/cluster/api/ClusterApiController.java` exposing endpoints defined in `contracts/cluster-ha-api.yaml`
- [ ] T017 [P] [US4] Create frontend API client in `frontend/src/services/clusterApi.ts` for cluster nodes, leader status, retention metrics, and on-demand triggers
- [ ] T018 [P] [US4] Implement `ClusterTopologyDashboard.tsx` in `frontend/src/components/cluster/ClusterTopologyDashboard.tsx` displaying connected nodes, leader election badge, heartbeat age, and retention statistics
- [ ] T019 [US4] Integrate `ClusterTopologyDashboard` into `frontend/src/App.tsx` and connect live SSE events (`LEADER_ELECTED`, `RETENTION_PRUNED`)

**Checkpoint**: User Story 4 is fully functional and testable independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Unify dashboard header indicators, execute end-to-end verification, and run full regression tests.

- [ ] T020 Update `frontend/src/components/telemetry/TelemetryHeader.tsx` to display cluster leader status badge and node count
- [ ] T021 Execute end-to-end verification scenario per `quickstart.md` and run full regression test suite `mvn test -B`
- [ ] T022 Verify frontend production build with `npm run build` in `frontend/` directory

---

## Dependencies & Execution Order

```
[Phase 1: Setup (T001-T003)]
       │
       ▼
[Phase 2: Foundational (T004-T006)]
       │
       ├────────────────────────┬────────────────────────┐
       ▼                        ▼                        ▼
[Phase 3: US1 Lease Reaper] [Phase 4: US2 Leader]   [Phase 5: US3 Retention]
(T007-T009)                 (T010-T012)             (T013-T015)
       │                        │                        │
       └────────────────────────┼────────────────────────┘
                                │
                                ▼
                     [Phase 6: US4 Dashboard & Telemetry]
                     (T016-T019)
                                │
                                ▼
                     [Phase 7: Polish & Regression]
                     (T020-T022)
```

### Parallel Opportunities

- In **Phase 1**: `T001`, `T002`, `T003` can all execute concurrently across schema, backend DTOs, and frontend types.
- In **Phase 2**: `T005` and `T006` can be implemented in parallel.
- Across **User Stories**: Once Phase 2 is complete, `US1`, `US2`, and `US3` frontend and backend tasks can proceed in parallel.
- All test tasks (`T007`, `T010`, `T013`) are parallelizable and should be written before implementations.

---

## Implementation Strategy (MVP First)

1. **Step 1**: Complete Phase 1 (Setup) and Phase 2 (Foundational).
2. **Step 2**: Implement User Story 1 (In-Flight Lease Recovery Reaper) $\rightarrow$ Validate crash healing independently.
3. **Step 3**: Implement User Story 2 (Distributed Leader Coordination) $\rightarrow$ Validate leader failover independently.
4. **Step 4**: Implement User Story 3 (Automated Idempotency Retention Pruning) $\rightarrow$ Validate safe batch pruning independently.
5. **Step 5**: Implement User Story 4 (Cluster Topology Telemetry & Dashboard View) $\rightarrow$ Validate UI cluster view.
6. **Step 6**: Complete Phase 7 Polish, run `mvn test -B` and `npm run build` to verify 100% green build.
