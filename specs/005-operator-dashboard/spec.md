# Feature Specification: Operator Management Dashboard

**Feature Branch**: `005-operator-dashboard`  
**Created**: 2026-11-15  
**Status**: Draft  
**Input**: User description: "Implement Milestone 5: Operator Management Dashboard (React + TypeScript). Build a modern real-time operator dashboard with an interactive Saga Flow DAG visualizer showing live color-coded step execution statuses (Success, Processing, Compensating, Failed), an Idempotency Inspector to query records by key and payload hash, a Manual Intervention Console enabling operators to trigger bounded force-retries or manually settle stuck/halted sagas into compensated state, and WebSocket/SSE real-time streaming backend endpoints in Spring Boot for live state updates and event broadcasting."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Real-Time Interactive Saga Flow DAG Visualizer (Priority: P1) 🎯 MVP

As an operations engineer or site reliability engineer (SRE), when monitoring active, completed, or failed distributed sagas, I want an interactive visual DAG (Directed Acyclic Graph) representation of each saga pipeline with real-time color-coded step status indicators, so that I can immediately comprehend execution topology, pinpoint failing or compensating steps, and inspect step payloads, fencing tokens, and error traces without digging through raw database tables or disparate server logs.

**Why this priority**: Core observability requirement (Constitution Principle V). When high-value financial transfers fail or enter backward compensation, operators must instantly identify the exact failure point, the steps successfully compensated, and any in-flight branches.

**Independent Test**: Can be verified by triggering saga executions (happy path, compensation rollback, and parallel branches). The visualizer renders all nodes and edges corresponding to the saga definition, applies live color codes (Green: Completed, Blue/Yellow: Executing, Orange: Compensating, Red: Failed, Gray: Pending), and dynamically updates node states in real time via live streaming as the backend transitions through steps.

**Acceptance Scenarios**:

1. **Given** an active or historical saga instance, **When** an operator navigates to the saga detail view, **Then** the dashboard displays an interactive DAG topology representing all forward steps, parallel branches, and compensation nodes.
2. **Given** a saga currently executing, **When** state machine transitions occur in the backend, **Then** node status colors update in real time without requiring manual browser page refreshes.
3. **Given** any step node in the DAG, **When** the operator clicks the node, **Then** a detail drawer opens displaying step metadata, input/output JSON payloads, start/end timestamps, fencing tokens, and error stack traces (if failed).
4. **Given** a saga with parallel execution branches (e.g., customer notification alongside ledger recording), **When** viewed in the DAG, **Then** the visualizer displays parallel child branches radiating from the parent fork and converging at the subsequent join node.

---

### User Story 2 - Idempotency Inspector & Forensic Verification (Priority: P2)

As a financial compliance officer or customer support engineer, when investigating duplicate charge disputes, suspected replay attacks, or delayed webhook notifications, I want a forensic Idempotency Inspector that allows searching records by idempotency key, tenant ID, or SHA-256 payload digest, so that I can audit lock lifecycle states, verify payload integrity, and confirm whether subsequent requests were served directly or replayed from cache.

**Why this priority**: Upholds financial integrity and auditability (Constitution Principles I & V). Provides direct operational visibility into the dual-stage idempotency gate, detecting payload tampering attempts and verifying lease expirations.

**Independent Test**: Can be verified by querying the Idempotency Inspector using known keys, hash values, and tenant IDs. The inspector displays matching records with lock statuses (`Pending`, `Processing`, `Resolved`, `Failed`), lease remaining time, original SHA-256 digest, response HTTP status, and cached response body. Replays with mismatched payloads are clearly flagged as tampering conflicts.

**Acceptance Scenarios**:

1. **Given** an idempotency key or SHA-256 payload hash, **When** an operator searches via the inspector, **Then** the system returns matching idempotency records with current status, lock timestamps, lease TTL, and cached response payloads.
2. **Given** a request where the client attempted to reuse an existing idempotency key with a mutated payload, **When** viewed in the inspector, **Then** the record highlights a "Payload Tampering Detected" alert with side-by-side hash comparison.
3. **Given** a stuck in-flight transaction with an active distributed lock lease, **When** inspected, **Then** the operator can view remaining lease duration, fencing tokens, and contender acquisition counts.

---

### User Story 3 - Manual Intervention Console for Stuck/Halted Sagas (Priority: P1)

As an authorized system operator, when a distributed saga enters a halted or unresolvable ambiguous state due to catastrophic downstream partner outages, permanent circuit breaker trips, or network partitioning, I want an administrative intervention console to trigger a safe, bounded force-retry or manually mark the saga as compensated with a mandatory audit justification, so that stuck financial workflows can be safely unstuck and settled without risking double-spend or silent data corruption.

**Why this priority**: Unresolvable transactions freeze account balances and violate SLA commitments. A controlled, audited intervention mechanism is essential for operational recovery while strictly preserving financial invariant constraints (Constitution Principles I & II).

**Independent Test**: Can be verified by simulating a saga halted in `COMPENSATING` or `FAILED` state with an expired lease. An operator initiates a manual resolution action (retry step or force-compensate) via the console, providing a reason note. The backend atomically updates the state machine, records an immutable `OPERATOR_INTERVENTION` audit event, and transitions the saga to terminal `COMPENSATED` state.

**Acceptance Scenarios**:

1. **Given** a saga in a non-terminal error state (`FAILED` or `COMPENSATING`) with an expired lease, **When** an operator clicks "Force Settle as Compensated", **Then** the console requires an intervention reason string before submitting the request.
2. **Given** an approved manual intervention command, **When** executed by the backend, **Then** the saga state transitions to `COMPENSATED`, preceding steps verify compensation actions, and an immutable `OPERATOR_INTERVENTION` audit event is recorded with the operator ID, timestamp, and justification.
3. **Given** an operator attempting to intervene on an actively executing saga with an unexpired lease, **When** the intervention is submitted, **Then** the system rejects the command with a conflict error to prevent race conditions with running workers.
4. **Given** a step failed due to a transient dependency outage, **When** an operator triggers a "Bounded Force Retry", **Then** the orchestrator re-executes only the failed step with a refreshed lease and fencing token.

---

### User Story 4 - Live Event Streaming & Operational Telemetry Feed (Priority: P3)

As an operations team lead, I want a real-time monitoring feed and metrics dashboard displaying live state updates, transactional outbox dispatch rates, dead letter queue (DLQ) alerts, and circuit breaker health statuses, so that I have situational awareness of system health and can proactively spot anomalies before customer impact occurs.

**Why this priority**: Operational visibility and alerting. Enables proactive remediation of upstream failures, poison pill buildups, and tripped circuit breakers across tenants.

**Independent Test**: Can be verified by establishing a live streaming connection (SSE/WebSocket) to the dashboard. As transactions, outbox dispatches, and circuit breaker state changes occur in the backend, events arrive on the client feed in real time with sub-second latency and increment live dashboard counters.

**Acceptance Scenarios**:

1. **Given** an active dashboard session, **When** the browser establishes a real-time stream connection, **Then** live state changes (saga transitions, outbox events, lock states) stream to the UI in real time.
2. **Given** an outbox event transitioning to `DEAD_LETTER` after retry exhaustion, **When** the event occurs, **Then** a prominent alert banner appears in the dashboard feed indicating an unpublishable poison pill event.
3. **Given** an external service circuit breaker tripping to `OPEN`, **When** the state change occurs, **Then** the dashboard telemetry panel immediately displays the degraded dependency with failure rate metrics.

---

### Edge Cases

- **Network Disconnection During Live Stream**: When the browser loses network connectivity, the dashboard must show a "Reconnecting..." indicator, automatically retry connection with exponential backoff, and fetch a state snapshot upon reconnection to reconcile missed events.
- **Concurrent Operator Interventions**: If two operators attempt to manually resolve the same saga simultaneously, the backend must use optimistic locking / fencing tokens so that exactly one intervention succeeds and the second receives a descriptive concurrency conflict warning.
- **Large Saga Payloads**: For sagas with multi-megabyte payloads or large step outputs, the DAG inspector must lazy-load payloads and format JSON with collapsible tree nodes to prevent browser UI freezing.
- **High-Throughput Event Spikes**: Under peak load (e.g. 100+ events/sec), the dashboard must throttle UI re-renders using batching/debouncing to maintain 60 FPS visual smoothness.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide an interactive Directed Acyclic Graph (DAG) visualizer displaying all steps, branches, and compensations of a selected saga instance.
- **FR-002**: The DAG visualizer MUST dynamically color-code nodes according to step execution state: Green (`COMPLETED`), Blue/Yellow (`EXECUTING`), Orange (`COMPENSATING`), Red (`FAILED`), and Gray (`NOT_STARTED` / `PENDING`).
- **FR-003**: The visualizer MUST render parallel execution branches distinctly, showing concurrent paths diverging from a common parent and joining at downstream stages.
- **FR-004**: Clicking any step node MUST reveal a detailed side drawer showing input payload, output response, start/completion timestamps, duration, fencing tokens, and error stack traces.
- **FR-005**: The system MUST provide an Idempotency Inspector allowing search by `Idempotency-Key`, tenant ID, status, or SHA-256 payload digest.
- **FR-006**: The Idempotency Inspector MUST flag payload tampering mismatches where an incoming request key matches an existing record but carries an altered payload digest.
- **FR-007**: The system MUST provide a Manual Intervention Console permitting authorized operators to trigger bounded force-retries or force-settle stuck sagas into terminal `COMPENSATED` state.
- **FR-008**: Every manual intervention MUST require a non-empty operator justification note and record an immutable `OPERATOR_INTERVENTION` audit event.
- **FR-009**: Manual interventions MUST be rejected if the target saga is actively executing with an unexpired lease, preventing split-brain or double-spend races.
- **FR-010**: The backend MUST expose real-time streaming endpoints (Server-Sent Events / WebSocket) broadcasting state machine transitions, outbox publications, and circuit breaker status changes.
- **FR-011**: The dashboard MUST display an Outbox and Dead Letter Queue (DLQ) monitor showing pending count, in-flight count, and quarantined poison pills.
- **FR-012**: The dashboard MUST display live status indicators for all registered Resilience4j circuit breakers (`CLOSED`, `HALF_OPEN`, `OPEN`) and current failure rates.
- **FR-013**: The dashboard client MUST gracefully handle stream disconnections with automatic reconnection and state reconciliation.
- **FR-014**: The system MUST support pagination and timestamp filtering for saga instance lists and audit logs.

---

### Key Entities *(include if feature involves data)*

- **SagaGraphView**: Representation of a saga definition and instance as graph nodes (steps/branches) and directed edges (dependencies/sequence), enriched with live runtime execution state.
- **OperatorInterventionCommand**: Administrative action model containing `sagaId`, `actionType` (`FORCE_RETRY`, `FORCE_COMPENSATE`), `stepIndex`, `operatorId`, `justification`, and `timestamp`.
- **DashboardLiveEvent**: Real-time event payload pushed over streaming channels containing `eventType`, `aggregateType`, `aggregateId`, `tenantId`, `timestamp`, and state delta payload.
- **IdempotencyForensicView**: Comprehensive view of an idempotency entry combining Redis lease state, PostgreSQL journal record, payload hash comparison, and cached response.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Operators can visually identify failing steps, error causes, and affected branches within 5 seconds of opening a saga in the DAG visualizer.
- **SC-002**: Live state updates appear in the visualizer within 500 milliseconds of backend state transitions.
- **SC-003**: Idempotency and payload hash forensic searches complete and render results in under 1 second across databases containing over 100,000 records.
- **SC-004**: Manual interventions successfully resolve stuck ambiguous sagas into a consistent terminal state with 100% audit logging and zero duplicate fund transfers.
- **SC-005**: Dashboard stream recovers and resynchronizes state within 2 seconds following transient network reconnection.

---

## Assumptions

- Operators access the management dashboard through modern desktop web browsers supporting WebSockets and modern SVG/Canvas rendering.
- The backend Spring Boot service runs in an environment with direct relational database access and can serve HTTP, WebSocket, and SSE traffic.
- Initial dashboard authentication relies on role-based operator permissions (`OPERATOR` or `ADMIN` role required for intervention commands).
- The dashboard is optimized for operational oversight and incident management; analytical data warehousing and multi-month historical reporting are handled out-of-band.
