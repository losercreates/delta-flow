# Research & Technical Decisions: Transactional Outbox & Resilience Policies

## Decision 1: Transactional Outbox Storage & Schema

### Decision
Create a dedicated `outbox_events` table in PostgreSQL. Domain events are persisted within the exact same database transaction as saga state mutations (`SagaJournalStore`).

### Rationale
- **Zero Dual-Write Hazard**: When an application writes to a database and publishes to a message broker/network endpoint in the same workflow, network or process failure inevitably leads to inconsistency (db written but message lost, or message sent but db rolled back). Persisting the event in PostgreSQL guarantees atomic commit or rollback.
- **At-Least-Once Delivery**: The outbox relay daemon reads committed events and publishes them with acknowledgement before updating status to `PUBLISHED`.
- **Auditability**: Unsent, in-flight, published, and failed events are stored with immutable timestamps for SOC2/PCI-DSS compliance.

### Alternatives Considered
- **Change Data Capture (CDC) via Debezium/Kafka**: Requires deploying and operating Apache Kafka and Debezium connector clusters. Deemed unnecessary operational complexity for Milestone 4; in-database polling satisfies scale requirements with zero new infrastructure dependencies.
- **Two-Phase Commit (XA Transactions)**: High latency, introduces long-held distributed locks, and is unsupported across standard HTTP/REST participants.

---

## Decision 2: Autonomous Outbox Relay Engine

### Decision
Implement `OutboxPublisher` as an autonomous `@Scheduled` daemon running every 1 second (configurable). It queries:
```sql
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
ORDER BY created_at ASC 
LIMIT :batchSize 
FOR UPDATE SKIP LOCKED;
```
It claims a batch in an atomic transaction, publishes events to registered `DomainEventPublisher` handlers, and marks them `PUBLISHED` upon confirmed dispatch.

### Rationale
- `FOR UPDATE SKIP LOCKED` eliminates contention between multiple horizontal instances without needing distributed Redis locks for the poller.
- Decoupling claim from publishing ensures that slow external endpoints do not hold database row locks longer than necessary.

### Alternatives Considered
- **Redis Queue Relay**: Pushing outbox events to a Redis queue during transaction commit introduces a dual-write sync problem between PostgreSQL and Redis.
- **Synchronous Post-Commit Listeners (`@TransactionalEventListener`)**: Only works in-memory on the active node; if the JVM crashes immediately after commit, the event is permanently lost without a background poller.

---

## Decision 3: Resilience4j Circuit Breakers & Bounded Retries

### Decision
Incorporate `io.github.resilience4j:resilience4j-spring-boot3` (v2.2.0). Wrap saga step actions programmatically using `CircuitBreaker.decorateSupplier(...)` and `Retry.decorateSupplier(...)`.

### Rationale
- Declarative saga steps defined in `SagaBuilder` use functional lambdas (`(ctx, input) -> ...`). Programmatic decoration allows attaching named circuit breakers and retries cleanly to individual steps (e.g. `.step("credit-step").withCircuitBreaker("creditService").withRetry("creditService")`).
- Exponential backoff with jitter prevents the "thundering herd" problem when downstream services experience transient outages.
- Fast-failure when a circuit is `OPEN` prevents worker thread exhaustion and immediately initiates saga compensation.

### Alternatives Considered
- **Spring `@CircuitBreaker` Annotations**: Requires creating proxy-based Spring bean methods for each step, which breaks the fluent, inline nature of `SagaBuilder`.
- **Custom Try-Catch Retry Loops**: Reinvents the wheel, lacks sliding-window state tracking, and does not provide JMX/Actuator health metrics.

---

## Decision 4: Java 21 Structured Concurrency for Parallel Branches

### Decision
Extend `SagaBuilder` with `.parallelStep("step-name", branchA, branchB, ...)` using Java 21 `StructuredTaskScope.ShutdownOnFailure` and Virtual Threads (`Thread.ofVirtual().factory()`).

### Rationale
- **Thread Safety & Zero Leaks**: In standard async pipelines (`CompletableFuture.allOf`), if one branch fails, the other branches continue executing as orphaned zombies. `StructuredTaskScope` ensures that when one branch fails, all sibling subtasks are cleanly interrupted and cancelled before the parent method returns.
- **Virtual Threads**: Parallel branches (e.g., sending customer notifications and updating analytics) can block on I/O without consuming heavy OS platform threads.

### Alternatives Considered
- **`CompletableFuture.allOf(...)`**: Does not provide structured thread lifecycles or automatic sibling cancellation on failure.
- **Sequential Execution**: Unnecessarily increases latency for independent I/O tasks.

---

## Decision 5: Poison Pill Quarantine & Dead Letter Handling

### Decision
If an outbox event repeatedly fails publishing and reaches `max_retries` (default: 5), transition its status to `DEAD_LETTER`, record the terminal stack trace in `error_details`, and emit a structured audit log `OUTBOX_DEAD_LETTER`.

### Rationale
- Guarantees that a single unpublishable message (poison pill) does not block subsequent valid transactions (avoids Head-of-Line blocking).
- Leaves the quarantined event in PostgreSQL for operator inspection and potential manual re-queueing via dashboard in Milestone 5.

