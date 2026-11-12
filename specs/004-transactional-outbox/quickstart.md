# Quickstart: Transactional Outbox & Resilience Policies

This guide demonstrates end-to-end validation of DeltaFlow's Transactional Outbox, Resilience4j circuit breakers/retries, and Java 21 Structured Concurrency.

---

## Prerequisites

- Java 21 LTS
- Docker / Docker Desktop running (for Testcontainers PostgreSQL 16 & Redis 7)
- Maven 3.9+

---

## Scenario 1: Atomic Outbox Event Persistence on Transfer Completion

When a transfer saga completes, domain events are written atomically to `outbox_events` in PostgreSQL within the same database transaction.

### 1. Initiate Transfer

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: outbox-demo-001" \
  -d '{
    "sourceAccountId": "acc_source_101",
    "targetAccountId": "acc_target_202",
    "amount": 350.00,
    "currency": "USD"
  }'
```

### 2. Verify Outbox Record Created

```bash
curl -i http://localhost:8080/api/v1/outbox?status=PENDING
```

Expected Response:
```json
[
  {
    "eventId": "a7b8c9d0-1234-5678-9abc-def012345678",
    "aggregateType": "SAGA_INSTANCE",
    "aggregateId": "...",
    "eventType": "TRANSFER_COMPLETED",
    "status": "PENDING",
    "retryCount": 0
  }
]
```

---

## Scenario 2: Autonomous Outbox Relay Event Publishing

The `OutboxPublisher` background daemon scans pending outbox events using `FOR UPDATE SKIP LOCKED`, dispatches them to destinations, and marks them `PUBLISHED`:

```bash
# Verify status transitions to PUBLISHED after relay scan cycle (within 1-2s)
curl -i http://localhost:8080/api/v1/outbox?status=PUBLISHED
```

Expected Audit Log:
```json
{"eventType":"OUTBOX_RECORDED","aggregateType":"SAGA_INSTANCE","eventType":"TRANSFER_COMPLETED"}
{"eventType":"OUTBOX_PUBLISHED","eventId":"a7b8c9d0-1234-5678-9abc-def012345678","status":"PUBLISHED"}
```

---

## Scenario 3: Resilience4j Circuit Breaker & Retry Under Network Errors

Simulates transient 5xx errors on an external service (e.g. credit gateway). DeltaFlow automatically absorbs transient blips up to 3 retry attempts with exponential backoff. When failures exceed 50%, the circuit breaker trips to `OPEN`:

```bash
mvn test -Dtest=SagaResilienceIntegrationTest
```

Expected Behavior:
- Retries transient errors 3 times before recording failure.
- Circuit transitions to `OPEN`.
- Subsequent calls immediately fast-fail with `CallNotPermittedException`.
- Saga coordinator initiates deterministic backward compensation without hanging threads.

---

## Scenario 4: Java 21 Structured Concurrency for Parallel Branches

Executes concurrent actions within a saga step (e.g. customer notification and analytical recording) on virtual threads:

```bash
mvn test -Dtest=SagaStructuredConcurrencyIntegrationTest
```

Expected Behavior:
- Parallel subtasks run concurrently on Virtual Threads.
- If one branch fails, `StructuredTaskScope.ShutdownOnFailure` cancels sibling branches immediately.
- Zero thread leaks or unhandled zombie tasks.

---

## Scenario 5: Outbox Poison Pill Quarantine (Dead Letter Queue)

When an outbox event suffers repeated permanent delivery errors exceeding 5 retries, the relay marks it `DEAD_LETTER` without blocking subsequent events:

```bash
mvn test -Dtest=OutboxDeadLetterIntegrationTest
```

Expected Audit Log:
```json
{"eventType":"OUTBOX_DEAD_LETTER","eventId":"...","details":{"retryCount":5,"errorDetails":"Fatal destination rejection"}}
```

