# Research & Technical Decisions: Backward Compensation, Crash Recovery & Zombie Fencing

## Decision 1: Reverse Compensation Rollback Strategy

### Context
In multi-step financial workflows, a failure can occur at any step $K$ in a pipeline of $N$ steps. DeltaFlow requires a strictly deterministic mechanism to undo all completed operations (steps $0$ through $K-1$) without stranded funds.

### Decision
Execute compensating actions sequentially in strict reverse order (`failedStepIndex - 1` down to `0`).
For each step $i$:
1. Check if step $i$ was recorded as `COMPLETED` in the write-ahead log.
2. Update step $i$ status to `COMPENSATING` in PostgreSQL within an isolated transaction.
3. Emit `STEP_COMPENSATING` audit event.
4. Execute the registered `BiConsumer<SagaExecutionContext, I> compensation` handler, providing access to the original input and the step's forward output context.
5. Upon successful execution, update step $i$ status to `COMPENSATED` and emit `STEP_COMPENSATED`.
6. Once all prior steps are compensated, update the saga instance status to `COMPENSATED` and emit `SAGA_COMPENSATED`.

### Rationale
Financial compensations often have direct dependencies on execution order. For example, in an account transfer (`Debit` $\rightarrow$ `Credit` $\rightarrow$ `Ledger`), if `Credit` fails, `Debit` must be refunded, but `Ledger` was never executed and requires no rollback. Sequential reverse execution eliminates race conditions between compensation actions.

### Alternatives Considered
- **Parallel Compensation**: Discarded because credit card reversals, account credits, and ledger balancing often have causal order dependencies.
- **Forward-Only Retry (No Compensation)**: Discarded because business errors (e.g., account frozen, insufficient funds) are non-transient and will fail indefinitely without rollback.

---

## Decision 2: Monotonic Fencing Tokens for Zombie-Proofing

### Context
When a downstream call experiences severe latency or network partitioning, the coordinator may time out and trigger backward compensation. If the original delayed downstream thread eventually completes ("zombie" execution), it must not be allowed to commit state changes or execute subsequent steps against a compensated saga.

### Decision
Implement a monotonic 64-bit integer `fencing_token` on `saga_steps`.
1. When step intent is logged, `fencing_token` is incremented.
2. Outbound step requests receive the active fencing token.
3. Upon receiving a response, the coordinator compares the response token against the database's current fencing token. If the token is less than the current token, or if the step/saga status is `COMPENSATING` or `COMPENSATED`, the response is unconditionally discarded.
4. An audit event `ZOMBIE_STEP_REJECTED` is logged with the stale token and current epoch.

### Rationale
Monotonic fencing tokens provide linearizable safety in asynchronous distributed systems (Kleppmann, DDIA Chapter 9). Even in the presence of GC pauses, network partitions, and clock drift, stale requests are deterministically rejected.

### Alternatives Considered
- **Wall-Clock Expiration Alone**: Discarded because system clock jumps or unpredictable thread suspension (e.g. JVM safepoints) can allow a zombie thread to execute after its deadline without detection.
- **Distributed Lock Across Remote Call Duration**: Discarded because holding distributed locks across unbounded remote RPC calls exhausts lock resources and leads to cascading timeouts.

---

## Decision 3: Crash Recovery Scanner Architecture

### Context
If a DeltaFlow instance crashes or restarts mid-flight, sagas can remain stranded in `EXECUTING` or `COMPENSATING` states.

### Decision
Create a `SagaRecoveryScanner` service running on application startup (`ApplicationReadyEvent`) and periodically via `@Scheduled(fixedDelayString = "${deltaflow.saga.recovery.poll-interval:5000}")`.
- The scanner executes a query:
  ```sql
  SELECT s FROM SagaInstance s 
  WHERE s.status IN ('EXECUTING', 'COMPENSATING') 
    AND s.updatedAt < :leaseThreshold
  ```
  using PostgreSQL pessimistic locking (`PESSIMISTIC_WRITE` with `SKIP LOCKED` hint).
- For claimed sagas in `EXECUTING`:
  - Inspect the last recorded step in `saga_steps`.
  - If the last step is `FAILED` or timed out, trigger backward compensation.
  - If the last step is `COMPLETED`, resume forward execution from the next step.
- For claimed sagas in `COMPENSATING`:
  - Resume the backward compensation loop from the current uncompensated step index down to 0.

### Rationale
`FOR UPDATE SKIP LOCKED` enables seamless horizontal clustering without node coordination protocols (like Raft or ZooKeeper). Multiple DeltaFlow pods can safely poll the database simultaneously; each expired saga is claimed by exactly one pod with zero contention.

### Alternatives Considered
- **Redis-Based Polling**: Discarded because PostgreSQL is the authoritative system of record for the saga write-ahead log; using Redis introduces a dual-write sync hazard during recovery.
- **External Workflow Orchestrator (e.g., Temporal/Cadence)**: Discarded to maintain DeltaFlow's lightweight embedded architectural profile without external cluster dependencies.

---

## Decision 4: Terminal Idempotency Integration on Compensation

### Context
A client submitting a payment with an `Idempotency-Key` that fails and is compensated must receive a definitive response. Replays of the same key must return the cached compensation result without re-executing.

### Decision
When `compensateSaga` finishes:
1. Update `saga_instances` to `COMPENSATED`.
2. Invoke `IdempotencyCoordinator.saveResolvedResponse` (or specialized terminal updater) with HTTP 422 Unprocessable Entity (or appropriate financial error code) and a JSON payload detailing the failure reason and that compensation/refund completed.
3. Client replays with the same `Idempotency-Key` are resolved from the cache with `Idempotency-Status: CACHED`.

### Rationale
Guarantees Constitution Principle I (Zero Double-Spend) across the failure lifecycle.

