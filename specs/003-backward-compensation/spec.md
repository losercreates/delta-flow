# Feature Specification: Backward Compensation, Crash Recovery & Zombie Fencing

**Feature Branch**: `003-backward-compensation`  
**Created**: 2026-11-11  
**Status**: Draft  
**Input**: User description: "Implement Milestone 3: Backward Compensation, Crash Recovery & Zombie Fencing. Build the reverse rollback execution engine with deterministic compensation on step failure, monotonic fencing tokens to reject late-arriving zombie steps, and a startup crash recovery scanner that detects expired leases on boot and resumes or compensates uncompleted sagas."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Automatic Backward Compensation on Downstream Step Failure (Priority: P1)

As a financial platform client initiating a multi-step money transfer, when a downstream step fails (e.g., the destination account is frozen or the credit service rejects the payment), I want DeltaFlow to automatically execute reverse compensation actions for all previously completed steps in strict reverse order (e.g., refunding the debited source account), so that the system leaves zero orphaned debits or unbalanced ledgers.

**Why this priority**: Non-negotiable financial invariance (Constitution Principle I & II). In distributed transactions, failures in later steps without automated compensation result in stranded customer funds and balance discrepancies.

**Independent Test**: Can be verified by executing a transfer where the `credit-step` is configured or mocked to fail. The orchestrator must halt forward progression, mark `credit-step` as `Failed`, invoke `debit-step` compensation (refunding the source account), update `debit-step` to `Compensated`, transition the saga instance to `Compensated`, and return an error response to the client indicating the transfer was aborted and refunded.

**Acceptance Scenarios**:

1. **Given** a 3-step saga (`Debit` -> `Credit` -> `Ledger`) where step 1 (`Debit`) completes successfully, **When** step 2 (`Credit`) fails with a functional or infrastructure error, **Then** DeltaFlow halts forward execution, transitions the saga status to `Compensating`, and dispatches the compensating action for step 1 in reverse order.
2. **Given** a completed compensation action, **When** the refund or undo operation succeeds, **Then** DeltaFlow records the step status as `Compensated` in the journal store with the compensation output payload.
3. **Given** all completed forward steps have been compensated, **When** the compensation loop finishes, **Then** DeltaFlow transitions the saga instance to `Compensated` and updates the idempotency record with a terminal failure response so client replays receive the final status without re-triggering transactions.

---

### User Story 2 - Crash Recovery Scanner on Startup & Lease Expiration (Priority: P1)

As a platform operations engineer, if an orchestrator instance abruptly crashes or is terminated mid-flight while executing or compensating a saga, I want a startup and periodic recovery scanner to identify orphaned sagas whose execution leases have expired, so that the workflow safely resumes forward execution or completes backward compensation without manual intervention.

**Why this priority**: Network nodes and container pods restart, scale, or fail unpredictably. Without automated lease recovery, transactions interrupted by node death remain frozen indefinitely in intermediate states (`Executing` or `Compensating`).

**Independent Test**: Can be verified by inserting a saga record stuck in `Executing` with an expired lease timestamp (simulating a dead worker process). On scanner activation, the system claims the expired lease, evaluates the last recorded step in the write-ahead log, and either resumes forward execution or triggers backward compensation to settle the saga cleanly.

**Acceptance Scenarios**:

1. **Given** a saga instance in state `Executing` whose step lease has expired past the configured timeout, **When** the crash recovery scanner executes (on boot or periodic scan), **Then** it atomically acquires lease ownership via database-level pessimistic locking.
2. **Given** an acquired expired saga where intermediate steps succeeded before the crash, **When** the recovery worker evaluates the journal store, **Then** it continues the workflow from the last uncompleted step or initiates compensation if the step is deemed unrecoverable.
3. **Given** an acquired expired saga in state `Compensating` that was interrupted before finishing rollback, **When** the recovery worker resumes, **Then** it resumes executing remaining uncompensated steps in reverse order until the saga reaches `Compensated`.

---

### User Story 3 - Monotonic Fencing Tokens & Zombie Step Protection (Priority: P2)

As a financial safety system, when a slow or partitioned downstream call times out and triggers compensation, I want DeltaFlow to issue monotonic fencing tokens so that if the slow "zombie" step eventually finishes late, its late-arriving execution or response is unconditionally rejected and prevented from mutating the state of the compensated transaction.

**Why this priority**: Network latency often mimics failure; a remote service may actually process a request seconds after the caller gave up and timed out. Fencing tokens prevent "split-brain" states where compensation refunds a debit, but the delayed credit completes later anyway (Constitution Principle II).

**Independent Test**: Can be verified by initiating compensation for a timed-out step, then having a simulated delayed thread attempt to submit a completion payload with the original (stale) fencing token. The coordinator must reject the completion with a fencing token mismatch error and leave the compensated state untouched.

**Acceptance Scenarios**:

1. **Given** an outbound step dispatch, **When** the coordinator prepares the call, **Then** it generates and attaches a monotonically increasing fencing token / epoch to the step execution.
2. **Given** a step that timed out and whose saga has transitioned to `Compensating` or `Compensated`, **When** the delayed remote response arrives bearing a stale fencing token, **Then** DeltaFlow discards the response, logs a `ZOMBIE_STEP_REJECTED` audit event, and prohibits any state updates.
3. **Given** a downstream participant supporting fencing tokens, **When** receiving a call with a lower token than the latest observed epoch, **Then** the participant rejects the mutation.

---

### User Story 4 - Declarative Compensation Registration & Context Delivery (Priority: P3)

As a workflow author, I want to define compensating actions alongside forward actions in the declarative saga builder API, with full access to the execution context and output of the corresponding forward step, so that writing safe reversible pipelines is intuitive and type-safe.

**Why this priority**: Minimizes developer error when authoring compensations and ensures rollback actions have the necessary references (e.g., transaction ID, debit authorization code) generated during the forward run.

**Independent Test**: Can be verified by defining a custom saga step with `.action(...)` and `.compensate(...)`, verifying that the compensation handler receives both the original input payload and the forward step's output context.

**Acceptance Scenarios**:

1. **Given** a step definition in `SagaBuilder`, **When** adding a step, **Then** the developer can specify an optional backward compensation handler: `step("debit-step", debitAction, debitCompensation)`.
2. **Given** the compensation handler executing during rollback, **When** invoked, **Then** it receives the original saga input, the step's specific request payload, and the response payload returned by the forward execution.

---

### Edge Cases

- **Double Failure During Compensation**: If a backward compensation action itself encounters a transient error (e.g., network blip), the coordinator records the failure, maintains the saga in `Compensating`, and allows exponential backoff retry or recovery scanning. If compensation permanently fails, the saga transitions to `Failed` with full diagnostic error details and an alert for manual operator intervention.
- **Idempotency of Compensating Actions**: Because network failures or node crashes can interrupt compensation mid-flight, all compensation handlers MUST be strictly idempotent; executing the same refund action multiple times must yield the exact same refund state.
- **Failure on Step 0 (First Step)**: If the very first step (`debit-step`) fails before producing state changes, no backward compensation is required; the saga immediately transitions to `Failed`.
- **Concurrent Recovery Contention**: If multiple cluster instances run the crash recovery scanner simultaneously, relational pessimistic locking (`FOR UPDATE SKIP LOCKED`) ensures each expired saga is claimed by at most one worker node.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a declarative API method on `SagaBuilder` to register an optional compensation action for each forward step.
- **FR-002**: System MUST transition saga instance status from `Executing` to `Compensating` when any forward step throws an unhandled exception or returns a failure status.
- **FR-003**: System MUST execute compensating actions in strict reverse sequential order of completed forward steps (e.g., if steps 0 and 1 completed and step 2 failed, execute compensation for step 1, then step 0).
- **FR-004**: System MUST pass the forward step's request payload and response payload into the compensation execution context.
- **FR-005**: System MUST update the `saga_steps` status in PostgreSQL to `Compensating` before dispatching the compensation, and to `Compensated` upon successful completion.
- **FR-006**: System MUST update the `saga_instances` status to `Compensated` once all previously completed steps have been successfully compensated.
- **FR-007**: System MUST assign a monotonically increasing fencing token / epoch to each step execution attempt in the write-ahead log.
- **FR-008**: System MUST reject any step completion or state transition attempt whose fencing token does not match the active step epoch.
- **FR-009**: System MUST implement a `SagaRecoveryScanner` scheduled component that runs on application startup and at a configurable periodic interval (e.g., 5 seconds).
- **FR-010**: System MUST query for orphaned sagas in `Executing` or `Compensating` status whose lease has expired using pessimistic locking (`SELECT ... FOR UPDATE SKIP LOCKED`).
- **FR-011**: System MUST automatically resume forward execution or initiate backward compensation for claimed orphaned sagas based on last committed journal state.
- **FR-012**: System MUST update the terminal `idempotency_records` entry upon saga compensation so subsequent client retries receive a consistent terminal response with `Idempotency-Status: CACHED`.
- **FR-013**: System MUST emit structured JSON audit log events for all compensation and recovery events: `SAGA_COMPENSATING`, `STEP_COMPENSATING`, `STEP_COMPENSATED`, `SAGA_COMPENSATED`, `ZOMBIE_STEP_REJECTED`, `RECOVERY_CLAIMED`, `RECOVERY_COMPLETED`.
- **FR-014**: System MUST implement reference compensation logic for the account transfer demo:
  1. `debit-step` compensation: Releases held funds or credits back the source account for the debited amount.
  2. `credit-step` compensation: Debits the destination account if funds were erroneously credited before downstream failure.
  3. `ledger-step` compensation: Reverses or voids journal entries.

---

### Key Entities *(include if feature involves data)*

- **SagaInstance**: Represents the high-level workflow instance. Updated statuses include `COMPENSATING`, `COMPENSATED`, `FAILED`. Attributes: `sagaId`, `tenantId`, `idempotencyKey`, `status`, `currentStep`, `payload`, `resultPayload`.
- **SagaStep**: Represents each discrete step in the journal store. Updated statuses include `COMPENSATING`, `COMPENSATED`, `FAILED`. Attributes: `stepId`, `sagaId`, `stepName`, `stepIndex`, `status`, `fencingToken`, `requestPayload`, `responsePayload`, `errorDetails`, `leaseExpiresAt`.
- **FencingToken**: Monotonic identifier ensuring linear execution and preventing zombie mutation.
- **RecoveryLease**: Expiration timestamp and worker owner token associated with active step execution.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of failed multi-step transfers automatically execute compensating transactions for prior completed steps, resulting in zero net balance loss or stranded debits across 500 test failure injections.
- **SC-002**: When a step fails at index N, compensations for steps N-1 down to 0 execute and complete within 500 milliseconds under normal network conditions.
- **SC-003**: 100% of late-arriving responses from timed-out zombie steps are rejected with fencing token mismatch errors, causing 0 state alterations after compensation.
- **SC-004**: 100% of interrupted sagas caused by sudden process termination are detected by the crash recovery scanner within 1 scan cycle of lease expiry and settled cleanly.
- **SC-005**: Replaying an idempotency key for a compensated transaction returns the cached failure/compensation response within 20 milliseconds without re-invoking downstream services.

---

## Assumptions

- Compensation operations are designed to be deterministic and idempotent by downstream service providers.
- Relational database clocks (PostgreSQL `CURRENT_TIMESTAMP`) provide the source of truth for lease expiration comparisons.
- The default step lease duration is 5 seconds, and the default recovery scan interval is 5 seconds (configurable via application properties).
- Network dispatches are synchronous within virtual threads or HTTP clients with bounded connection and read timeouts.

