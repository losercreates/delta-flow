# Tasks: Real-Time Streaming & Chaos Control in Operator Dashboard

**Input**: Design documents from `specs/009-dashboard-chaos-control/` (`plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/chaos-dashboard-api.yaml`, `quickstart.md`)  
**Prerequisites**: `plan.md`, `spec.md`, `data-model.md`, `contracts/`  
**Constitution Alignment**: Principle I (Zero Double-Spend), Principle II (Strict LIFO Compensation), Principle III (Java 21 Type Safety), Principle IV (TDD & Chaos Verification via Testcontainers), Principle V (Immutable Audit Trail).  

## Format: `- [ ] [TaskID] [P?] [Story?] Description with file path`

- **[P]**: Can run in parallel (independent files and dependencies)
- **[Story]**: User Story label ([US1], [US2], [US3], [US4])

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish common frontend and backend types and configuration for chaos and live streaming.

- [ ] T001 [P] Define chaos and invariant TypeScript interfaces in `frontend/src/types/chaos.ts`
- [ ] T002 [P] Extend live event types (`SAGA_SUSPENDED`, `SAGA_RESUMED`, `CHAOS_INJECTED`, `CHAOS_HEALED`, `CIRCUIT_BREAKER_TRANSITIONED`, `INVARIANT_CHECKED`, `OUTBOX_REPLAYED`) and DLQ DTOs in `frontend/src/types/dashboard.ts`
- [ ] T003 [P] Create Chaos DTO records (`ChaosStatusDto.java`, `ProxyStatusDto.java`, `ToxicDetailDto.java`, `ChaosInjectionRequest.java`, `CircuitBreakerTransitionRequest.java`) in `src/main/java/io/deltaflow/chaos/domain/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core backend service infrastructure required before user stories can execute.

- [ ] T004 Create DLQ DTO records (`DeadLetterOutboxEventDto.java`, `DlqReplayRequest.java`) in `src/main/java/io/deltaflow/dashboard/dto/`
- [ ] T005 [P] Implement `ChaosControlService.java` in `src/main/java/io/deltaflow/chaos/service/` with Toxiproxy client bridge, Resilience4j `CircuitBreakerRegistry` integration, and safe simulation mode fallback
- [ ] T006 Update `DashboardEventBroadcaster.java` in `src/main/java/io/deltaflow/dashboard/service/` to support all extended event types with structured payload broadcasting

**Checkpoint**: Foundational models and services ready — user story implementation can begin.

---

## Phase 3: User Story 1 - Real-Time Saga Streaming & Dynamic DAG Transition Visualization (Priority: P1) 🎯 MVP

**Goal**: Deliver real-time SSE streaming of saga transitions (including `SUSPENDED`, `RESUMED`, `COMPENSATED`), reactive DAG node visualization, and automatic client reconnection.

**Independent Test**: Trigger a suspended saga via REST API; verify that `GET /api/v1/dashboard/stream` broadcasts `STEP_STATE_CHANGED` and `SAGA_SUSPENDED`, and the frontend DAG visualizer dynamically highlights the waiting node and updates status badges without page refresh.

### Tests for User Story 1 ⚠️
- [ ] T007 [P] [US1] Create integration test `DashboardStreamIntegrationTest.java` in `src/test/java/io/deltaflow/dashboard/DashboardStreamIntegrationTest.java` verifying SSE connection, heartbeat emissions, and event dispatching for saga transitions

### Implementation for User Story 1
- [ ] T008 [US1] Wire saga coordinator suspension, resumption, and timeout events into `DashboardEventBroadcaster` in `src/main/java/io/deltaflow/saga/coordinator/SagaCoordinator.java`
- [ ] T009 [P] [US1] Enhance `frontend/src/components/dag/StepNodeCard.tsx` to visually render `SUSPENDED` / `WAITING_FOR_EVENT` status with amber badge and timeout countdown
- [ ] T010 [P] [US1] Update `frontend/src/components/dag/SagaFlowDag.tsx` to dynamically animate edges and highlight suspended nodes upon receiving live SSE events
- [ ] T011 [US1] Enhance `frontend/src/hooks/useDashboardStream.ts` with exponential backoff auto-reconnect and handler dispatching for extended live event types

**Checkpoint**: User Story 1 is fully functional and testable independently (MVP ready).

---

## Phase 4: User Story 2 - Interactive Chaos Control Console & Fault Injection (Priority: P1) 🎯 MVP

**Goal**: Provide an interactive Chaos Control panel allowing operators to inject network faults (latency, packet loss, partitions) and toggle circuit breaker states with live telemetry.

**Independent Test**: Submit a chaos injection request via UI or `POST /api/v1/chaos/inject`; verify that Toxiproxy applies the toxic condition, `CHAOS_INJECTED` is broadcast over SSE, and clicking "Heal All" restores healthy conditions.

### Tests for User Story 2 ⚠️
- [ ] T012 [P] [US2] Create integration test `ChaosControlIntegrationTest.java` in `src/test/java/io/deltaflow/chaos/api/ChaosControlIntegrationTest.java` validating `/api/v1/chaos/status`, `/api/v1/chaos/inject`, `/api/v1/chaos/heal`, and circuit breaker transitions

### Implementation for User Story 2
- [ ] T013 [US2] Implement `ChaosControlController.java` in `src/main/java/io/deltaflow/chaos/api/ChaosControlController.java` exposing endpoints defined in `contracts/chaos-dashboard-api.yaml`
- [ ] T014 [P] [US2] Create frontend API client in `frontend/src/services/chaosApi.ts` for chaos status, fault injection, healing, and circuit breaker manipulation
- [ ] T015 [P] [US2] Implement `ChaosInjectorPanel.tsx` in `frontend/src/components/chaos/ChaosInjectorPanel.tsx` with target proxy selector, latency slider, packet drop toggle, and "Heal All" button
- [ ] T016 [P] [US2] Implement `CircuitBreakerConsole.tsx` in `frontend/src/components/chaos/CircuitBreakerConsole.tsx` with live circuit status cards and manual state toggle buttons (`OPEN`, `CLOSED`, `HALF_OPEN`)
- [ ] T017 [US2] Implement `ChaosControlDashboard.tsx` in `frontend/src/components/chaos/ChaosControlDashboard.tsx` assembling the injector panel and circuit breaker console into a cohesive tab

**Checkpoint**: User Story 2 is fully functional and testable independently.

---

## Phase 5: User Story 3 - One-Click Financial Invariant Verification & Real-Time Audit Reporting (Priority: P2)

**Goal**: Expose an interactive invariant audit tool in the dashboard to execute arithmetic balance checks ($\sum\text{Debits} = \sum\text{Credits} + \sum\text{Refunds}$), detect orphaned debits, and display pass/fail report cards in real time.

**Independent Test**: Click "Run Invariant Audit" in the dashboard; verify that `GET /api/v1/invariants/verify` runs, broadcasts `INVARIANT_CHECKED`, and renders the visual audit card with debit/credit balance confirmation and zero violations.

### Tests for User Story 3 ⚠️
- [ ] T018 [P] [US3] Create integration test `InvariantVerificationApiIntegrationTest.java` in `src/test/java/io/deltaflow/chaos/api/InvariantVerificationApiIntegrationTest.java` validating the invariant endpoint and SSE notification dispatch

### Implementation for User Story 3
- [ ] T019 [US3] Update `InvariantVerificationController.java` in `src/main/java/io/deltaflow/chaos/api/InvariantVerificationController.java` to broadcast `INVARIANT_CHECKED` live event via `DashboardEventBroadcaster` upon audit completion
- [ ] T020 [P] [US3] Implement `InvariantAuditPanel.tsx` in `frontend/src/components/chaos/InvariantAuditPanel.tsx` displaying on-demand verification trigger, pass/fail status badge, total audited volume, and violation details
- [ ] T021 [US3] Integrate `InvariantAuditPanel.tsx` into `frontend/src/components/chaos/ChaosControlDashboard.tsx` with reactive auto-refresh on `INVARIANT_CHECKED` SSE events

**Checkpoint**: User Story 3 is fully functional and testable independently.

---

## Phase 6: User Story 4 - Dead-Letter Queue (DLQ) Forensics & Manual Intervention (Priority: P2)

**Goal**: Enable Tier-2 operators to inspect dead-lettered outbox events, view error details, and trigger safe replays or manual force-compensations with mandatory justification and audit logging.

**Independent Test**: Inject a poison outbox event into `outbox_events` marked as `DEAD_LETTER`; verify it appears in `GET /api/v1/dashboard/dlq`, and invoking `POST /api/v1/dashboard/dlq/{id}/replay` resets status to `PENDING` and dispatches the event.

### Tests for User Story 4 ⚠️
- [ ] T022 [P] [US4] Create integration test `DlqManagementIntegrationTest.java` in `src/test/java/io/deltaflow/dashboard/DlqManagementIntegrationTest.java` verifying DLQ querying, replay resetting, and audit logging

### Implementation for User Story 4
- [ ] T023 [US4] Add DLQ query and replay methods to `DashboardService.java` in `src/main/java/io/deltaflow/dashboard/service/DashboardService.java` resetting `DEAD_LETTER` to `PENDING` and broadcasting `OUTBOX_REPLAYED`
- [ ] T024 [US4] Expose `GET /api/v1/dashboard/dlq` and `POST /api/v1/dashboard/dlq/{eventId}/replay` in `DashboardApiController.java` in `src/main/java/io/deltaflow/dashboard/api/DashboardApiController.java`
- [ ] T025 [P] [US4] Add `fetchDeadLetterEvents` and `replayDeadLetterEvent` functions to `frontend/src/services/dashboardApi.ts`
- [ ] T026 [P] [US4] Implement `DlqInspectorModal.tsx` in `frontend/src/components/chaos/DlqInspectorModal.tsx` with poison message viewer, error stack trace preview, and replay confirmation dialog with mandatory justification
- [ ] T027 [US4] Integrate DLQ inspector trigger into `frontend/src/components/chaos/ChaosControlDashboard.tsx` and `frontend/src/components/telemetry/TelemetryHeader.tsx`

**Checkpoint**: User Story 4 is fully functional and testable independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Unify dashboard navigation, integrate all new views into main app, and execute full end-to-end regression verification.

- [ ] T028 Update `frontend/src/App.tsx` to add "Chaos & Invariants" as the 3rd primary navigation tab with active toxic indicators and event counter badges
- [ ] T029 Update `frontend/src/components/telemetry/TelemetryHeader.tsx` to display active chaos and DLQ count badges alongside existing saga counters
- [ ] T030 Execute end-to-end verification scenario per `quickstart.md` and run full regression test suite `mvn test -B`
- [ ] T031 Verify frontend production build with `npm run build` in `frontend/` directory

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
[Phase 3: US1 Streaming]  [Phase 4: US2 Chaos]     [Phase 5: US3 Invariants]
(T007-T011)               (T012-T017)              (T018-T021)
       │                        │                        │
       └────────────────────────┼────────────────────────┘
                                │
                                ▼
                     [Phase 6: US4 DLQ Forensics]
                     (T022-T027)
                                │
                                ▼
                     [Phase 7: Polish & Regression]
                     (T028-T031)
```

### Parallel Opportunities

- In **Phase 1**: `T001`, `T002`, `T003` can all execute concurrently across frontend and backend DTOs.
- In **Phase 2**: `T005` and `T006` can be implemented in parallel.
- Across **User Stories**: Once Phase 2 is complete, `US1`, `US2`, and `US3` frontend and backend tasks can proceed in parallel.
- All test tasks (`T007`, `T012`, `T018`, `T022`) are parallelizable and should be written before implementations.

---

## Implementation Strategy (MVP First)

1. **Step 1**: Complete Phase 1 (Setup) and Phase 2 (Foundational).
2. **Step 2**: Implement User Story 1 (Real-Time Saga Streaming & DAG Transitions) $\rightarrow$ Validate MVP live streaming independently.
3. **Step 3**: Implement User Story 2 (Chaos Control Console & Fault Injection) $\rightarrow$ Validate Toxiproxy / Circuit Breaker controls independently.
4. **Step 4**: Implement User Story 3 (One-Click Invariant Verification) $\rightarrow$ Validate zero double-spend audit independently.
5. **Step 5**: Implement User Story 4 (DLQ Forensics & Replay) $\rightarrow$ Validate poison message recovery independently.
6. **Step 6**: Complete Phase 7 Polish, run `mvn test -B` and `npm run build` to verify 100% green build.
