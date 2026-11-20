# Feature Specification: Real-Time Streaming & Chaos Control in Operator Dashboard

**Feature Branch**: `009-dashboard-chaos-control`  
**Created**: 2026-11-20  
**Status**: Draft  
**Input**: User description: "Real-Time Streaming & Chaos Control in Operator Dashboard: Enhance the Operator Dashboard with live event streaming for saga state transitions (including WAITING_FOR_EVENT, SUSPENDED, and RESUMED), interactive chaos experiment controls (Toxiproxy latency/jitter, packet drop, circuit breaker state triggers), real-time financial invariant verification telemetry (zero double-spend, conservation of balances), and DLQ inspection with manual replay/intervention controls."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Real-Time Saga Streaming & Dynamic DAG Transition Visualization (Priority: P1) 🎯 MVP

As an operations engineer or site reliability engineer, when distributed financial sagas execute, suspend, or compensate, I want the operator dashboard to stream live state transitions and step progress in real time without manual page reloads, so that I have immediate visibility into high-value monetary flows, asynchronous waiting states, and rollback operations as they occur.

**Why this priority**: Operational visibility is essential for mission-critical financial orchestration. Live event updates eliminate stale state observations, empower operators to pinpoint stuck or failed steps instantly, and provide immediate visual verification of multi-step sagas.

**Independent Test**: Can be tested by opening the operator dashboard and initiating payment sagas (including forward execution, asynchronous suspension awaiting external webhooks, and compensation rollbacks); verifying that the visual DAG and status badges update automatically within 500 milliseconds of each state transition without manual browser refresh.

**Acceptance Scenarios**:

1. **Given** an open operator console viewing the saga flow visualizer, **When** a distributed saga is initiated or advances through its execution steps, **Then** the visual graph and status list dynamically update step states, elapsed time, and status indicators in real time via live streaming.
2. **Given** a saga entering an asynchronous waiting step (`SUSPENDED`), **When** the suspension occurs, **Then** the visual DAG immediately highlights the waiting node, displays the expected event name, and reflects the active timeout deadline.
3. **Given** a suspended saga receiving an external callback or webhook resumption, **When** the saga resumes forward execution, **Then** the node transitions from suspended to executing/completed without page reload.
4. **Given** a temporary network interruption between client and server, **When** connectivity is restored, **Then** the streaming connection automatically reconnects and re-synchronizes telemetry metrics.

---

### User Story 2 - Interactive Chaos Control Console & Fault Injection (Priority: P1) 🎯 MVP

As a reliability engineer or system auditor, I want an interactive chaos control panel in the operator dashboard where I can configure and inject controlled network faults (latency, packet loss, connection partitions) and trigger resilience policies, so that I can validate system fault tolerance, circuit breaker tripping, and recovery mechanisms in real time without command-line intervention.

**Why this priority**: Continuous verification under adverse conditions is a core architectural requirement (Constitution Principle IV). Operators and QA engineers require accessible, safe tooling to simulate production degradation and verify that financial consistency guarantees hold under partition and timeout scenarios.

**Independent Test**: Can be tested by opening the Chaos Control tab, selecting a target communication channel or proxy, applying a fault profile (e.g., 500ms latency or 30% packet loss), and verifying that: (a) the active fault state is visually displayed, (b) initiated sagas exhibit the injected delay or trigger resilience fallbacks, and (c) clicking "Heal All" cleanly resets all faults to baseline healthy operations.

**Acceptance Scenarios**:

1. **Given** the Chaos Control panel, **When** an operator selects a target service link and applies latency or packet drop conditions, **Then** the system injects the fault, updates the active chaos indicator badge, and emits an audit event recording the operator action.
2. **Given** active chaos injection, **When** new sagas execute, **Then** resilience policies (such as circuit breakers or bounded retries) respond according to configured thresholds, and their live states (Closed, Open, Half-Open) update on the dashboard.
3. **Given** an operator clicking "Heal / Reset All Chaos", **When** confirmed, **Then** all injected network faults and toxic conditions are immediately cleared, and the chaos indicator returns to healthy status.
4. **Given** circuit breaker telemetry on the dashboard, **When** an operator clicks a manual circuit breaker trip or reset control, **Then** the targeted circuit transitions immediately to the requested state and reflects in the telemetry summary.

---

### User Story 3 - One-Click Financial Invariant Verification & Real-Time Audit Reporting (Priority: P2)

As a financial controller or compliance officer, I want to trigger an automated financial invariant verification scan directly from the dashboard and inspect a real-time audit report, so that I can mathematically verify that no double-spends occurred, debit/credit totals are strictly conserved, and zero orphaned ledger entries exist.

**Why this priority**: Fulfills Constitution Principle I & V. In distributed financial architectures, operators need on-demand cryptographic and arithmetic proof that state consistency has not drifted during normal or chaos operations.

**Independent Test**: Can be tested by clicking "Verify Financial Invariants" in the dashboard, observing the verification progress indicator, and reviewing the generated report displaying total debited vs credited amounts, discrepancy count, double-spend detection, and per-saga forensic breakdown.

**Acceptance Scenarios**:

1. **Given** completed and compensated sagas across accounts, **When** an operator clicks "Verify Invariants", **Then** the system performs an atomic consistency scan across all account balances, journal entries, and idempotency records, presenting the result within 3 seconds.
2. **Given** a clean financial ledger with zero discrepancies, **When** verification completes, **Then** the report displays a green "PASSED" badge, confirming that Total Debited equals Total Credited + Total Refunded, with zero orphaned debits and zero double-spends.
3. **Given** any simulated or actual invariant violation (e.g. mismatched balances), **When** verification completes, **Then** the report displays a prominent red "VIOLATION DETECTED" banner, detailing the offending saga ID, tenant ID, and calculated variance.
4. **Given** multi-tenant operations, **When** an operator filters the verification by tenant ID, **Then** the audit computes invariants strictly for the specified tenant partition.

---

### User Story 4 - Dead-Letter Queue (DLQ) Forensics & Manual Saga Intervention (Priority: P2)

As a tier-2 operations support engineer, when a transaction encounters persistent downstream failure, DLQ poison-pill routing, or an indefinite timeout, I want to inspect dead-lettered messages and halted sagas with clear options to force deterministic compensation or replay failed dispatches, so that trapped financial states can be safely resolved with full audit accountability.

**Why this priority**: Critical operational safety valve. When unexpected external outages occur, operators must have auditable, constitutionally safe intervention mechanisms to prevent customer funds from remaining indefinitely locked.

**Independent Test**: Can be tested by locating a dead-lettered outbox event or failed/suspended saga in the dashboard, opening the intervention modal, selecting a permitted remediation action (such as Force Compensate or Replay DLQ), providing a mandatory justification, and verifying that the action settles the record and writes an immutable audit log.

**Acceptance Scenarios**:

1. **Given** outbox events routed to the Dead-Letter Queue (DLQ), **When** an operator navigates to the DLQ inspector, **Then** the dashboard displays all poison events with failure reason, retry counts, and payload preview.
2. **Given** a dead-lettered event, **When** the operator resolves downstream dependencies and clicks "Replay Event", **Then** the system re-attempts dispatch and removes the item from the DLQ upon successful publication.
3. **Given** a stuck ambiguous or timed-out saga, **When** an operator triggers "Force Compensate" with a mandatory text justification, **Then** the system executes deterministic backward compensation in LIFO order and records the operator ID and justification in the immutable audit log.

---

### Edge Cases

- **Massive Influx of Live Events**: When hundreds of concurrent sagas complete in a burst, the dashboard client must buffer or debounce visual graph updates to avoid UI stuttering or thread lock while keeping telemetry counters accurate.
- **Client Disconnection During Chaos Experiment**: If an operator closes or loses connection to the dashboard while chaos injection is active, the active chaos state must remain visible to other operators upon reconnection, and an optional auto-reset safety timeout must prevent perpetual degraded states.
- **Concurrent Invariant Verification**: If multiple operators initiate invariant verification simultaneously, the system must either deduplicate concurrent scans or return cached recent verification reports to prevent unnecessary database load.
- **Rapid Step Transitions**: When steps complete in sub-millisecond succession, the live streaming feed must preserve event ordering so that completion events do not arrive or render before initiation events.
- **Intervention on Already-Settled Saga**: If an operator submits an intervention request (e.g., Force Compensate) while the saga is simultaneously settling or compensating via automated background recovery, the system must detect the state race and reject the stale intervention with a clear status message.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST stream real-time saga lifecycle events to connected dashboard clients via continuous event streaming (e.g., Server-Sent Events / SSE or WebSockets).
- **FR-002**: Streamed event types MUST include saga initiation, step progress transitions, asynchronous waiting (`SUSPENDED`), webhook resumption (`RESUMED`), timeout expiration, compensation rollbacks, and terminal completions.
- **FR-003**: System MUST automatically dispatch a lightweight keep-alive heartbeat over the streaming channel at a regular interval (maximum 15 seconds) to prevent proxy connection timeouts.
- **FR-004**: Dashboard UI MUST automatically reconnect to the streaming endpoint with exponential backoff upon accidental connection severance.
- **FR-005**: Dashboard UI MUST update DAG node states, execution durations, and telemetry badges reactively upon receipt of streaming events without requiring full page reloading.
- **FR-006**: System MUST expose interactive Chaos Control endpoints allowing operators to inspect current chaos status, inject latency, inject packet loss, simulate connection drops, and reset all faults across configured proxies.
- **FR-007**: System MUST provide an endpoint to inspect and toggle circuit breaker states (Closed, Open, Half-Open) on demand for testing resilience fallbacks.
- **FR-008**: System MUST log every chaos injection, fault removal, and circuit breaker manipulation in an append-only audit trail capturing operator ID, action, target, and timestamp.
- **FR-009**: System MUST expose an on-demand financial invariant verification endpoint returning a structured audit report comparing total debited funds, total credited funds, total refunded funds, orphaned debits, and double-spend occurrences.
- **FR-010**: Dashboard UI MUST provide a dedicated "Chaos & Invariants" console tab featuring chaos injection controls, circuit breaker toggles, and one-click financial invariant verification with visual pass/fail indicator cards.
- **FR-011**: System MUST provide an endpoint and UI view to inspect Dead-Letter Queue (DLQ) outbox events and trigger manual event replays.
- **FR-012**: System MUST enforce that any manual intervention (Force Compensate, DLQ Replay) requires a non-empty operator identifier and text justification, strictly preserving LIFO compensation order.

### Key Entities

- **ChaosExperiment**: Represents an active or historical chaos injection containing target proxy/service, fault type (latency, packet loss, partition), parameters (delay milliseconds, drop rate percentage), status (active, cleared), initiated by operator, and timestamp.
- **InvariantVerificationReport**: Represents the output of a mathematical balance audit, containing total transactions audited, successful count, compensated count, failed count, total debits, total credits, total refunds, variance amount, violation list, and overall compliance status (PASSED/FAILED).
- **DeadLetterEvent**: Represents a poisoned or failed outbox event that exceeded maximum retry attempts, containing event ID, aggregate type, topic, payload, error stack trace, attempt count, and last retry timestamp.
- **DashboardLiveEvent**: Represents a lightweight notification envelope broadcast to dashboard clients, containing unique event ID, event type, saga ID, tenant ID, timestamp, and optional transition payload.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Dashboard clients reflect saga status transitions within 500 milliseconds of state persistence in the relational database.
- **SC-002**: Operators can activate or heal network chaos faults from the dashboard UI with a single click, taking effect across simulated dependencies in under 1 second.
- **SC-003**: Invariant verification report executes and renders in the dashboard in under 3 seconds for audit workloads up to 10,000 transaction records.
- **SC-004**: Zero double-spend incidents and 100% balance conservation verified under active chaos injection (30% packet loss, 500ms latency).
- **SC-005**: 100% of operator chaos interventions and remediation actions produce immutable audit log entries with verifiable operator attribution.
- **SC-006**: Streaming client automatically recovers and re-synchronizes telemetry state within 3 seconds of network reconnection after a transient disconnection.

---

## Assumptions

- Toxiproxy or equivalent mock proxy harness is available in testing and staging environments for chaos experiments; in production environments without proxy sidecars, the chaos control endpoints can safely operate in simulation/read-only mode.
- Operators accessing the dashboard are authenticated internal users with appropriate permissions to inspect financial telemetry and initiate remediation actions.
- Client browser supports standard Server-Sent Events (`EventSource`) or WebSocket protocols.
- Financial transactions in the system adhere to the standard double-entry bookkeeping convention where debits and credits must balance across completed or compensated workflows.
