# Quickstart: Backward Compensation, Crash Recovery & Zombie Fencing

This guide demonstrates end-to-end validation of DeltaFlow's backward compensation, zombie rejection, and crash recovery scanner.

---

## Prerequisites

- Java 21 LTS
- Docker / Docker Desktop running (for Testcontainers PostgreSQL 16 & Redis 7)
- Maven 3.9+

---

## Scenario 1: Automated Backward Compensation on Step Failure

When a multi-step transfer fails at step 2 (`credit-step`), DeltaFlow halts progression and automatically triggers the compensation handler for step 1 (`debit-step`), refunding the customer.

### 1. Initiate Transfer with Simulated Downstream Failure

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-fail-credit-001" \
  -d '{
    "sourceAccountId": "acc_source_100",
    "targetAccountId": "acc_frozen_error",
    "amount": 250.00,
    "currency": "USD"
  }'
```

### Expected Response

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/json
Idempotency-Status: DIRECT

{
  "sagaId": "b18b4562-b7e1-4c19-9486-5627236d9341",
  "idempotencyKey": "demo-fail-credit-001",
  "status": "COMPENSATED",
  "failedStep": "credit-step",
  "errorMessage": "Target account frozen or credit rejected",
  "compensatedSteps": [
    "debit-step"
  ]
}
```

### 2. Verify Audit Log

```json
{"eventType":"STEP_EXECUTING","stepName":"debit-step","stepIndex":0}
{"eventType":"STEP_COMPLETED","stepName":"debit-step","stepIndex":0}
{"eventType":"STEP_EXECUTING","stepName":"credit-step","stepIndex":1}
{"eventType":"STEP_FAILED","stepName":"credit-step","stepIndex":1,"errorDetails":"Target account frozen or credit rejected"}
{"eventType":"SAGA_COMPENSATING","sagaId":"b18b4562-b7e1-4c19-9486-5627236d9341"}
{"eventType":"STEP_COMPENSATING","stepName":"debit-step","stepIndex":0}
{"eventType":"STEP_COMPENSATED","stepName":"debit-step","stepIndex":0}
{"eventType":"SAGA_COMPENSATED","sagaId":"b18b4562-b7e1-4c19-9486-5627236d9341"}
```

---

## Scenario 2: Idempotent Replay of Compensated Transfer

Re-submitting the exact same request with the same `Idempotency-Key` returns the cached terminal response without re-executing debit or refund:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-fail-credit-001" \
  -d '{
    "sourceAccountId": "acc_source_100",
    "targetAccountId": "acc_frozen_error",
    "amount": 250.00,
    "currency": "USD"
  }'
```

### Expected Response

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/json
Idempotency-Status: CACHED

{
  "sagaId": "b18b4562-b7e1-4c19-9486-5627236d9341",
  "idempotencyKey": "demo-fail-credit-001",
  "status": "COMPENSATED",
  "failedStep": "credit-step",
  "errorMessage": "Target account frozen or credit rejected",
  "compensatedSteps": [
    "debit-step"
  ]
}
```

---

## Scenario 3: Zombie Step Rejection Verification

Simulates a slow step that times out, triggers compensation, and then attempts late completion with a stale fencing token:

```bash
mvn test -Dtest=SagaZombieFencingIntegrationTest
```

Expected log assertion:
```json
{"eventType":"ZOMBIE_STEP_REJECTED","details":{"staleToken":1,"activeToken":2,"stepName":"credit-step"}}
```

---

## Scenario 4: Crash Recovery Scanner on Boot

Simulates a process crash leaving a saga in `EXECUTING` with an expired lease. On startup, `SagaRecoveryScanner` detects the expired lease and rolls back uncompleted state:

```bash
mvn test -Dtest=SagaCrashRecoveryIntegrationTest
```

Expected assertion:
- Scanner claims saga with `RECOVERY_CLAIMED`.
- Compensates step 0 and transitions saga to `COMPENSATED`.

