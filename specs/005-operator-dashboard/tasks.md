# Tasks: Operator Management Dashboard

**Input**: Design documents from `/specs/005-operator-dashboard/`  
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)  
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (`[US1]`, `[US2]`, `[US3]`, `[US4]`)
- Exact file paths included in all descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Frontend project scaffolding, build tooling, and shared UI libraries

- [ ] T001 Initialize React 18 + TypeScript + Vite project in `frontend/package.json`, `frontend/tsconfig.json`, and configure development API proxy in `frontend/vite.config.ts`
- [ ] T002 [P] Configure Tailwind CSS, PostCSS, and Lucide icons in `frontend/tailwind.config.js`, `frontend/postcss.config.js`, and `frontend/src/index.css`
- [ ] T003 [P] Install and configure React Flow (`@xyflow/react`) and Dagre graph layout dependencies in `frontend/package.json`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared backend DTOs, frontend TypeScript types, and SSE event broadcast infrastructure required by all stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T004 [P] Create backend DTO records `SagaSummaryDto.java`, `SagaGraphDto.java`, `SagaNodeDto.java`, `SagaEdgeDto.java`, `OperatorInterventionRequest.java`, `OperatorInterventionResult.java`, `IdempotencyForensicDto.java`, and `TelemetrySummaryDto.java` in `src/main/java/io/deltaflow/dashboard/dto/`
- [ ] T005 [P] Create frontend TypeScript interfaces and type definitions matching the OpenAPI specification in `frontend/src/types/dashboard.ts`
- [ ] T006 Implement `DashboardEventBroadcaster.java` managing active `SseEmitter` subscriptions, heartbeat timers, and broadcasting `DashboardLiveEvent` in `src/main/java/io/deltaflow/dashboard/service/DashboardEventBroadcaster.java`
- [ ] T007 Integrate `DashboardEventBroadcaster` into `SagaCoordinator.java` to emit real-time state machine transition events (`SAGA_STARTED`, `STEP_STATE_CHANGED`, `SAGA_COMPLETED`, `SAGA_COMPENSATED`) in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Real-Time Interactive Saga Flow DAG Visualizer (Priority: P1) 🎯 MVP

**Goal**: Visualize distributed saga workflows as interactive directed acyclic graphs with live color-coded step execution statuses, step metadata drawers, and parallel branch rendering.

**Independent Test**: `mvn test -Dtest=DashboardGraphIntegrationTest` verifies `GET /api/v1/dashboard/sagas/{sagaId}/graph` returns nodes and edges with matching step states, and the React visualizer renders all nodes with correct color codes and interactive inspection drawers.

### Tests for User Story 1 ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T008 [P] [US1] Integration test verifying visual graph projection generation and step node state mapping in `src/test/java/io/deltaflow/dashboard/integration/DashboardGraphIntegrationTest.java`

### Implementation for User Story 1

- [ ] T009 [US1] Implement `DashboardService.java` with `getSagaGraph(UUID sagaId)` and `listSagas(...)` building `SagaGraphDto` with `nodes` and `edges` (including parallel branches and compensations) from `SagaJournalStore` in `src/main/java/io/deltaflow/dashboard/service/DashboardService.java`
- [ ] T010 [US1] Implement `GET /api/v1/dashboard/sagas` and `GET /api/v1/dashboard/sagas/{sagaId}/graph` endpoints in `src/main/java/io/deltaflow/dashboard/api/DashboardApiController.java`
- [ ] T011 [P] [US1] Build REST API client functions for sagas listing and graph projection retrieval in `frontend/src/services/dashboardApi.ts`
- [ ] T012 [P] [US1] Build custom React Flow node component `StepNodeCard.tsx` with color-coded status badges, step icons, execution timers, and retry counts in `frontend/src/components/dag/StepNodeCard.tsx`
- [ ] T013 [US1] Build interactive DAG component `SagaFlowDag.tsx` using `@xyflow/react` with Dagre auto-layout in `frontend/src/components/dag/SagaFlowDag.tsx`
- [ ] T014 [US1] Build node inspection detail drawer `StepDetailDrawer.tsx` displaying JSON payloads, fencing tokens, and error stack traces in `frontend/src/components/dag/StepDetailDrawer.tsx`

**Checkpoint**: User Story 1 is fully functional and independently verified (MVP delivered).

---

## Phase 4: User Story 3 - Manual Intervention Console for Stuck/Halted Sagas (Priority: P1)

**Goal**: Provide safe administrative controls for operators to force-retry or force-settle stuck/halted sagas into terminal `COMPENSATED` state with mandatory justification and audit logging, strictly guarding against double-spend races.

**Independent Test**: `mvn test -Dtest=OperatorInterventionIntegrationTest` verifies lease expiration validation (rejects active worker lease with 409 Conflict), transitions halted saga to `COMPENSATED`, allocates monotonic fencing tokens, and emits `OPERATOR_INTERVENTION` audit event.

### Tests for User Story 3 ⚠️

- [ ] T015 [P] [US3] Integration test verifying intervention lease validation, force-compensation, and audit event in `src/test/java/io/deltaflow/dashboard/integration/OperatorInterventionIntegrationTest.java`

### Implementation for User Story 3

- [ ] T016 [US3] Implement `OperatorInterventionService.java` enforcing active lease validation, monotonic fencing token increments, backward compensation invocation, and `OPERATOR_INTERVENTION` audit logging in `src/main/java/io/deltaflow/dashboard/service/OperatorInterventionService.java`
- [ ] T017 [US3] Implement `POST /api/v1/dashboard/sagas/{sagaId}/intervene` endpoint in `src/main/java/io/deltaflow/dashboard/api/DashboardApiController.java`
- [ ] T018 [P] [US3] Build intervention modal dialog `InterventionModal.tsx` in `frontend/src/components/intervention/InterventionModal.tsx` requiring operator ID and reason note before submission
- [ ] T019 [US3] Wire intervention actions into DAG view and saga detail view with confirmation toasts and error handling in `frontend/src/components/dag/SagaFlowDag.tsx`

**Checkpoint**: User Stories 1 AND 3 both work independently.

---

## Phase 5: User Story 2 - Idempotency Inspector & Forensic Search (Priority: P2)

**Goal**: Search, inspect, and verify idempotency records by key, tenant ID, or SHA-256 payload digest, highlighting tampering attempts and cached responses.

**Independent Test**: `mvn test -Dtest=IdempotencyForensicsIntegrationTest` verifies search by key and payload hash, lease expiration checks, and tampering detection.

### Tests for User Story 2 ⚠️

- [ ] T020 [P] [US2] Integration test verifying idempotency search and payload tampering detection in `src/test/java/io/deltaflow/dashboard/integration/IdempotencyForensicsIntegrationTest.java`

### Implementation for User Story 2

- [ ] T021 [US2] Extend `IdempotencyRecordRepository.java` and `DashboardService.java` with multi-attribute search and hash comparison logic in `src/main/java/io/deltaflow/dashboard/service/DashboardService.java`
- [ ] T022 [US2] Implement `GET /api/v1/dashboard/idempotency` endpoint in `src/main/java/io/deltaflow/dashboard/api/DashboardApiController.java`
- [ ] T023 [P] [US2] Build `IdempotencyInspector.tsx` table view with key/hash search bar, lock status badges, lease timers, and payload tampering mismatch alerts in `frontend/src/components/forensics/IdempotencyInspector.tsx`

**Checkpoint**: User Stories 1, 2, and 3 are functional.

---

## Phase 6: User Story 4 - Live Event Streaming & Operational Telemetry Feed (Priority: P3)

**Goal**: Real-time SSE streaming feed broadcasting state machine transitions, outbox dispatch events, and circuit breaker health metrics.

**Independent Test**: `mvn test -Dtest=DashboardStreamIntegrationTest` verifies SSE stream connection (`GET /api/v1/dashboard/stream`) and event delivery upon saga progression.

### Tests for User Story 4 ⚠️

- [ ] T024 [P] [US4] Integration test verifying SSE connection and live event broadcasting in `src/test/java/io/deltaflow/dashboard/integration/DashboardStreamIntegrationTest.java`

### Implementation for User Story 4

- [ ] T025 [US4] Implement `DashboardStreamController.java` handling `GET /api/v1/dashboard/stream` SSE subscriptions in `src/main/java/io/deltaflow/dashboard/api/DashboardStreamController.java`
- [ ] T026 [US4] Implement `GET /api/v1/dashboard/telemetry` aggregating active/completed saga counts, DLQ counts, and Resilience4j circuit breaker health in `src/main/java/io/deltaflow/dashboard/service/DashboardService.java`
- [ ] T027 [P] [US4] Build custom React hook `useDashboardStream.ts` in `frontend/src/hooks/useDashboardStream.ts` managing EventSource connection, auto-reconnect, and state updates
- [ ] T028 [US4] Build `TelemetryHeader.tsx` displaying live metrics, DLQ alert pill, and circuit breaker status chips in `frontend/src/components/telemetry/TelemetryHeader.tsx`

**Checkpoint**: Real-time event streaming and telemetry feed operational.

---

## Phase 7: Polish & Full-Stack Integration

**Purpose**: Single-page application navigation shell, end-to-end quickstart validation, and full regression verification

- [ ] T029 [P] Build top-level dashboard layout, navigation tabs, and saga selector in `frontend/src/App.tsx` uniting DAG visualizer, sagas list, idempotency inspector, and telemetry header
- [ ] T030 Run quickstart validation scenarios from `specs/005-operator-dashboard/quickstart.md`
- [ ] T031 Run full regression test suite `mvn test -B` with Testcontainers verifying all existing tests plus new Milestone 5 tests pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Phase 2 completion
  - User Story 1 (P1): Interactive DAG visualizer & graph projection (MVP)
  - User Story 3 (P1): Manual intervention console & safe compensation/retry
  - User Story 2 (P2): Idempotency forensic search & tampering detection
  - User Story 4 (P3): Real-time SSE streaming & telemetry feed
- **Polish (Phase 7)**: Depends on all user stories being complete

---

## Parallel Opportunities

- **Phase 1**: T002 (Tailwind) and T003 (React Flow) can run in parallel with T001.
- **Phase 2**: T004 (Java DTOs) and T005 (TypeScript types) can execute in parallel.
- **Phase 3 (US1)**: T008 (test), T011 (API client), and T012 (StepNodeCard) can execute in parallel.
- **Phase 4 (US3)**: T015 (test) and T018 (modal UI) can execute in parallel.
- **Phase 5 (US2)**: T020 (test) and T023 (table UI) can execute in parallel.
- **Phase 6 (US4)**: T024 (test) and T027 (SSE hook) can execute in parallel.
- **Phase 7**: T029 (App layout) can be authored while backend tests run.

---

## Implementation Strategy

### MVP First (User Story 1 Only)
1. Complete Phase 1: Setup (`frontend/` scaffolding, dependencies)
2. Complete Phase 2: Foundational (DTOs, TypeScript types, SSE broadcaster)
3. Complete Phase 3: User Story 1 (`DashboardGraphIntegrationTest`, `DashboardService`, `SagaFlowDag.tsx`)
4. Validate with `mvn test -Dtest=DashboardGraphIntegrationTest` (Delivers MVP DAG visualizer!)

### Incremental Delivery
1. Add Phase 4 (User Story 3: Manual Intervention Console) -> Safe recovery for stuck sagas
2. Add Phase 5 (User Story 2: Idempotency Forensics) -> Tampering detection & key audit
3. Add Phase 6 (User Story 4: Live Event Streaming & Telemetry) -> Real-time SSE updates & health feed
4. Complete Phase 7: Polish & Full Regression Suite (`mvn test -B`)
