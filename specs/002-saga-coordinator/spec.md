# Feature Specification: Declarative Saga Coordinator & Write-Ahead Log (WAL) Engine

**Feature Branch**: `002-saga-coordinator`  
**Created**: 2026-11-10  
**Status**: Draft  
**Input**: User description: "Milestone 2: Declarative Saga Coordinator & WAL Engine (Forward Path)"

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Happy-Path Multi-Step Financial Transfer (Priority: P1)

As a financial platform client, I want to execute an account-to-account money transfer that safely traverses multiple distinct service domains (debiting the source account, crediting the destination account, and recording the transaction in the central ledger) so that the money transfer completes reliably with full transactional integrity.

**Why this priority**: This represents the foundational business value of DeltaFlow: orchestrating a multi-service financial transaction sequentially from start to finish without manual intervention or fragmented state.

**Independent Test**: Can be verified by submitting an authorized transfer request ($500.00 from Account A to Account B) and confirming that all three steps (Debit, Credit, Ledger) complete successfully in order, returning HTTP 201 with completed transaction details.

**Acceptance Scenarios**:
1. **Given** valid source and destination accounts with sufficient balance, **When** a client initiates a transfer through DeltaFlow, **Then** the orchestrator sequentially executes Debit, Credit, and Ledger steps, transitions the saga to `Completed`, and returns the final transaction confirmation.
2. **Given** a successfully completed transfer saga, **When** inspecting the audit trail and database records, **Then** all three steps have status `Completed` with recorded timestamps and responses.

---

### User Story 2 - Write-Ahead Log (WAL) Intent Persistence (Priority: P1)

As a financial infrastructure engineer, I want the orchestrator to record each step's intent and payload in a durable relational store *before* dispatching any remote network request, so that if the system crashes or restarts mid-execution, an immutable record of planned and executed work exists.

**Why this priority**: Non-negotiable financial invariance (Constitution Principle I). Without write-ahead logging before network dispatches, power failure or process death between remote operations causes unrecoverable state loss.

**Independent Test**: Can be verified by intercepting or inspecting the database state before an outbound step execution and verifying that a `saga_steps` record in `Executing` state exists in PostgreSQL prior to the downstream service receiving the call.

**Acceptance Scenarios**:
1. **Given** a saga entering a new step, **When** the coordinator prepares to call the downstream service, **Then** it first writes the step record with status `Executing` and request payload to the relational journal store within an ACID transaction.
2. **Given** a successful downstream response, **When** the coordinator receives the reply, **Then** it immediately updates the step record to `Completed` along with the response payload before proceeding to the subsequent step.

---

### User Story 3 - Idempotent Saga Initiation and Deduping (Priority: P2)

As an API client integrating with financial gateways, I want to supply an `Idempotency-Key` header with my transfer request so that network timeouts or accidental client retries do not trigger duplicate saga runs or double-debits.

**Why this priority**: Essential to financial safety (Constitution Principle I). Guarantees that client retries attach to the ongoing or completed saga instance rather than spawning duplicate transactions.

**Independent Test**: Can be verified by submitting a transfer with an `Idempotency-Key`, then immediately submitting a second identical request. The second request must return the cached terminal response without invoking downstream step handlers a second time.

**Acceptance Scenarios**:
1. **Given** a completed saga with a given `Idempotency-Key`, **When** a client sends an identical request with the same key, **Then** DeltaFlow returns the cached terminal response with header `Idempotency-Status: CACHED` and zero downstream executions.
2. **Given** an actively executing saga, **When** a concurrent client request arrives with the same `Idempotency-Key`, **Then** the system detects active in-flight processing and prevents duplicate execution (returning 409 Conflict or waiting for resolution).
3. **Given** an existing saga, **When** a request arrives with the same `Idempotency-Key` but an altered payload (e.g., different amount), **Then** DeltaFlow rejects the request with HTTP 409 Conflict and a tampering error code.

---

### User Story 4 - Declarative Pipeline Definition & Context Propagation (Priority: P3)

As a software engineer building financial workflows, I want to define sagas using a clear, declarative fluent API specifying sequential steps, their actions, and data transformations, so that multi-step workflows are readable, maintainable, and type-safe.

**Why this priority**: Reduces boilerplate and human error when defining complex distributed workflows, enabling new financial workflows (e.g., currency exchange, escrow) to be built with identical safety guarantees.

**Independent Test**: Can be verified by defining a 3-step saga using the builder API and asserting that each step receives the accumulated results and context of preceding steps.

**Acceptance Scenarios**:
1. **Given** a declarative saga definition with steps `Debit`, `Credit`, and `Ledger`, **When** the saga executes, **Then** step 2 (`Credit`) has access to step 1's output (e.g., debit transaction reference), and step 3 (`Ledger`) has access to outputs from both prior steps.

---

### Edge Cases

- **Downstream Step Failure (e.g., Insufficient Funds)**: If the Debit service returns a functional failure (insufficient funds, invalid account), the coordinator halts forward progression immediately, marks the step as `Failed`, marks the saga as `Failed`, and returns an appropriate error response without calling Credit or Ledger.
- **Network Timeout on Outbound Call**: If a downstream service call times out, the step remains recorded as `Executing` or `Failed` with the error captured in the journal, preventing ambiguous forward continuation.
- **Concurrent Re-Entrancy**: If two threads attempt to execute the same saga instance concurrently, relational pessimistic locking (`PESSIMISTIC_WRITE`) serializes access, granting lock ownership to exactly one thread.
- **Large Response Payloads**: If a downstream service returns a large JSON body, the journal store captures the payload within bounded JSONB column limits without blocking database performance.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a declarative definition API for specifying ordered, sequential saga steps.
- **FR-002**: System MUST persist a `saga_instances` record containing the tenant ID, idempotency key, saga type, initial state (`Pending`), and input payload before initiating workflow execution.
- **FR-003**: System MUST persist a `saga_steps` record with status `Executing` and request payload to PostgreSQL before executing each outbound network call (Write-Ahead Log pattern).
- **FR-004**: System MUST update the step record in PostgreSQL with status `Completed` and response payload upon receiving a successful response.
- **FR-005**: System MUST enforce compile-time exhaustive state handling for all saga instance states (`Pending`, `Executing`, `Completed`, `Failed`, `Compensating`) and step states (`NotStarted`, `Executing`, `Completed`, `Failed`, `Compensated`).
- **FR-006**: System MUST wire incoming transfer requests to the Idempotency Gate (Milestone 1), verifying the `Idempotency-Key` and SHA-256 request payload digest.
- **FR-007**: System MUST immediately return the cached terminal response for duplicate requests matching an already completed saga's idempotency key.
- **FR-008**: System MUST reject duplicate requests with matching idempotency keys but mismatched payload digests with HTTP 409 Conflict.
- **FR-009**: System MUST pass an immutable execution context through the pipeline, allowing downstream steps to read outputs from preceding steps.
- **FR-010**: System MUST halt forward execution immediately if any step produces an unrecoverable failure or timeout.
- **FR-011**: System MUST update the terminal idempotency record with the final HTTP status code, response headers, and response payload upon saga completion or failure.
- **FR-012**: System MUST emit structured JSON audit log events for all saga and step lifecycle transitions (`SAGA_STARTED`, `STEP_EXECUTING`, `STEP_COMPLETED`, `SAGA_COMPLETED`, `SAGA_FAILED`).
- **FR-013**: System MUST provide a sequential 3-step reference implementation for account-to-account transfers:
  1. `debit-step`: Debits source account for requested amount.
  2. `credit-step`: Credits destination account for requested amount.
  3. `ledger-step`: Records double-entry journal entry with debit and credit references.

---

### Key Entities *(include if feature involves data)*

- **Saga Instance (`saga_instances`)**:
  - `saga_id` (UUID, Primary Key): Unique identifier for the saga execution.
  - `tenant_id` (String): Multi-tenant isolation partition key.
  - `idempotency_key` (String, Indexed): Client-provided token linking the saga to its idempotency gate entry.
  - `saga_type` (String): Identifier for the workflow definition (e.g., `ACCOUNT_TRANSFER`).
  - `status` (String): Current lifecycle state (`Pending`, `Executing`, `Completed`, `Failed`, `Compensating`).
  - `current_step` (Integer): Index of the actively running or most recently completed step.
  - `payload` (JSONB): Initial workflow input parameters.
  - `created_at` / `updated_at` (TIMESTAMPTZ): Audit and tracking timestamps.

- **Saga Step (`saga_steps`)**:
  - `step_id` (UUID, Primary Key): Unique identifier for the specific step execution record.
  - `saga_id` (UUID, Foreign Key): Reference to the parent saga instance.
  - `step_name` (String): Human-readable identifier for the step (e.g., `debit-source-account`).
  - `step_index` (Integer): Zero-based sequential position in the pipeline.
  - `status` (String): Step state (`NotStarted`, `Executing`, `Completed`, `Failed`, `Compensated`).
  - `request_payload` (JSONB): Data sent to the downstream participant.
  - `response_payload` (JSONB): Response data received from the downstream participant.
  - `error_details` (Text, Nullable): Diagnostic error message or stack trace if the step failed.
  - `lease_expires_at` (TIMESTAMPTZ, Nullable): Step-level lease deadline for timeout detection.
  - `created_at` / `updated_at` (TIMESTAMPTZ): Audit and tracking timestamps.

- **Saga Execution Context**:
  - Runtime object holding the immutable initial request, saga identifier, tenant ID, and an unmodifiable map of step outcomes from all previously completed steps.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of valid money transfer requests complete all 3 forward steps (Debit -> Credit -> Ledger) in exact sequence.
- **SC-002**: 100% of executed steps have a corresponding WAL record written and committed to PostgreSQL prior to outbound step handler invocation.
- **SC-003**: 0 duplicate saga instances or double-transfers created when concurrent requests submit identical idempotency keys.
- **SC-004**: Cached response replay for completed sagas returns in under 50ms at p99 latency without invoking any downstream service handlers.
- **SC-005**: End-to-end execution of a 3-step forward saga completes in under 250ms p95 latency under normal test conditions.
- **SC-006**: 100% test coverage across all defined acceptance scenarios using Testcontainers with real PostgreSQL and Redis instances.

---

## Assumptions

- **Target Scope**: Forward execution path only. Backward compensation rollback, crash recovery scanners, and zombie fencing are reserved for Milestone 3 per the project constitution.
- **Storage Layer**: PostgreSQL is the durable Write-Ahead Log store; Redis is utilized for distributed lock coordination and lease expiration.
- **Service Communication**: Downstream steps in the demo pipeline are executed via clean synchronous interfaces that can be backed by internal services or remote REST endpoints.
- **Tenant Isolation**: Sagas operate with tenant-level scoping defaulting to `'default'` when omitted by the client.

