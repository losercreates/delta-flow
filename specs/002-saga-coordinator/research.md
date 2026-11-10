# Phase 0 Research: Declarative Saga Coordinator & Write-Ahead Log (WAL) Engine

**Feature**: Milestone 2 - Declarative Saga Coordinator & WAL Engine (Forward Path)  
**Branch**: `002-saga-coordinator`  
**Date**: 2026-11-10  
**Status**: Completed  

---

## 1. Architectural Foundations & Problem Analysis

In distributed financial microservices, a transfer between two disparate accounts involves three distinct domain actions:
1. **Debit Source Account**: Decrements balance in Account A.
2. **Credit Destination Account**: Increments balance in Account B.
3. **Record Ledger Entry**: Updates the immutable financial ledger with double-entry journal proofs.

A simple REST or RPC chain is susceptible to partial failures (e.g. network disconnect between Debit and Credit), creating orphaned debits or financial leaks. To guarantee financial invariance (Constitution Principle I), DeltaFlow implements an **Orchestration Saga with a Write-Ahead Log (WAL)**:
- Every step's intent is durably committed to PostgreSQL before outbound network dispatch.
- Every state transition is recorded in an immutable journal.
- Re-entrancy and retries are guarded by the Idempotency Gate (Milestone 1).

---

## 2. Java 21 Sealed Hierarchy State Machine

### Investigation: Sealed Interfaces vs. Traditional State Enums
Traditional Java state machines use mutable POJOs or loose enums with switch statements that rely on runtime `default` branches, risking unhandled states when new states are added.

Java 21 sealed interfaces combined with record patterns provide:
1. **Compile-Time Exhaustion**: The compiler verifies that every permitted subtype is handled in a pattern-matching `switch`. Adding a state without handling it causes a compilation failure.
2. **Type-Safe Payloads**: Each state carries only the exact data valid for that lifecycle phase. For example, a `Completed` state contains the execution timestamp and step journal map, whereas a `Pending` state contains only initial inputs.

### Sealed Interface Design

```java
// Saga Instance State
public sealed interface SagaState permits 
    SagaState.Pending, 
    SagaState.Executing, 
    SagaState.Completed, 
    SagaState.Failed, 
    SagaState.Compensating {

    record Pending(UUID sagaId, String tenantId, String idempotencyKey, String sagaType, Map<String, Object> payload, Instant createdAt) implements SagaState {}
    record Executing(UUID sagaId, String tenantId, int currentStepIndex, String currentStepName, Instant stepStartedAt) implements SagaState {}
    record Completed(UUID sagaId, String tenantId, Map<String, Object> finalResult, Instant completedAt) implements SagaState {}
    record Failed(UUID sagaId, String tenantId, int failedStepIndex, String failureReason, Instant failedAt) implements SagaState {}
    record Compensating(UUID sagaId, String tenantId, int rollbackStepIndex, String cause) implements SagaState {}
}

// Saga Step State
public sealed interface SagaStepState permits 
    SagaStepState.NotStarted, 
    SagaStepState.Executing, 
    SagaStepState.Completed, 
    SagaStepState.Failed, 
    SagaStepState.Compensated {

    record NotStarted(UUID stepId, int stepIndex, String stepName) implements SagaStepState {}
    record Executing(UUID stepId, int stepIndex, String stepName, Map<String, Object> requestPayload, Instant startedAt) implements SagaStepState {}
    record Completed(UUID stepId, int stepIndex, String stepName, Map<String, Object> responsePayload, Instant completedAt) implements SagaStepState {}
    record Failed(UUID stepId, int stepIndex, String stepName, String errorDetails, Instant failedAt) implements SagaStepState {}
    record Compensated(UUID stepId, int stepIndex, String stepName, Instant compensatedAt) implements SagaStepState {}
}
```

---

## 3. PostgreSQL Write-Ahead Log (WAL) Journal Store

### Investigation: Database Locking Strategy
To prevent race conditions where duplicate requests attempt to advance the same saga instance simultaneously:
- **`PESSIMISTIC_WRITE` Locks**: When fetching an active saga instance to advance a step, Spring Data JPA executes `SELECT ... FOR UPDATE` on the `saga_instances` row.
- **Transaction Isolation**: Step execution uses `Propagation.REQUIRES_NEW` when recording step intent to guarantee that the WAL record is committed to disk before the outbound HTTP/REST request is sent over the wire.
- **Payload Storage**: Both request and response payloads are stored as PostgreSQL `JSONB` with `@JdbcTypeCode(SqlTypes.JSON)` for efficient querying and indexing.

### Relational Schema Design

```sql
CREATE TABLE saga_instances (
    saga_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    idempotency_key VARCHAR(128) NOT NULL,
    saga_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step INT NOT NULL DEFAULT 0,
    payload JSONB NOT NULL,
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_saga_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE saga_steps (
    step_id UUID PRIMARY KEY,
    saga_id UUID NOT NULL REFERENCES saga_instances(saga_id) ON DELETE CASCADE,
    step_name VARCHAR(64) NOT NULL,
    step_index INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_payload JSONB,
    response_payload JSONB,
    error_details TEXT,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_saga_step_index UNIQUE (saga_id, step_index)
);

CREATE INDEX idx_saga_instances_status ON saga_instances (status);
CREATE INDEX idx_saga_steps_saga_status ON saga_steps (saga_id, status);
```

---

## 4. Declarative Saga Definition Builder Pattern

### Investigation: Fluent API Design
A clean, functional API allows developers to define saga flows declaratively without coupling step logic to coordinator infrastructure.

```java
SagaDefinition<TransferRequest, TransferResult> transferSaga = SagaBuilder
    .<TransferRequest, TransferResult>named("account-transfer")
    .step("debit-source-account")
        .action((ctx, req) -> debitService.executeDebit(req.sourceAccountId(), req.amount()))
        .compensate((ctx, req) -> debitService.refundDebit(req.sourceAccountId(), req.amount()))
    .step("credit-destination-account")
        .action((ctx, req) -> creditService.executeCredit(req.targetAccountId(), req.amount()))
        .compensate((ctx, req) -> creditService.reverseCredit(req.targetAccountId(), req.amount()))
    .step("record-ledger-entry")
        .action((ctx, req) -> ledgerService.recordJournal(ctx.getStepResult("debit-source-account"), ctx.getStepResult("credit-destination-account")))
    .onCompletion((ctx, req) -> new TransferResult(ctx.getSagaId(), "COMPLETED", req.amount(), Instant.now()))
    .build();
```

---

## 5. Idempotency Gate Integration (Milestone 1 $\rightarrow$ Milestone 2)

### Coordination Workflow
1. Client sends `POST /api/v1/transfers` with `Idempotency-Key` and transfer body.
2. `IdempotencyFilter` and `IdempotencyCoordinator` intercept the request:
   - Computes SHA-256 payload digest.
   - If existing and `RESOLVED`: returns cached HTTP 201 response directly.
   - If payload altered: returns HTTP 409 Conflict.
   - If new: acquires lock and creates `PROCESSING` idempotency record.
3. Controller delegates to `SagaCoordinator`:
   - Checks if `saga_instances` exists for `(tenantId, idempotencyKey)`.
   - Creates `saga_instances` row in `Pending` state.
   - Executes 3 forward steps sequentially, persisting WAL journal records before each call.
   - Upon final step completion: marks saga as `Completed` and notifies `IdempotencyCoordinator` via `saveResolvedResponse`.
4. Downstream returns terminal HTTP 201 response to client.

---

## 6. Concurrency & Re-Entrancy Model

- **Database-Level Fencing**: The combination of `uq_saga_tenant_idempotency` and `PESSIMISTIC_WRITE` locks on the saga instance guarantees that two concurrent threads cannot advance the same saga simultaneously.
- **Thread Safety**: The `SagaExecutionContext` is immutable; each step produces a new immutable context containing the accumulated outcomes of previous steps.

---

## 7. Migration & Compatibility Strategy

- **Flyway Migration `V2__create_saga_tables.sql`**: Introduces `saga_instances` and `saga_steps` without altering `idempotency_records` created in `V1`.
- **Zero Breaking Changes**: Existing `PaymentController` (from Milestone 1 demo) continues to function. A new dedicated `TransferController` will demonstrate the full 3-step saga orchestrator.

