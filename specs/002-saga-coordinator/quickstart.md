# Quickstart: Declarative Saga Coordinator & WAL Engine

This guide demonstrates how to execute a 3-step distributed transfer saga (Debit $\rightarrow$ Credit $\rightarrow$ Ledger) and verify the PostgreSQL Write-Ahead Log (WAL) journals.

---

## Prerequisites

Start local dependencies or let Spring Boot Testcontainers bootstrap them automatically:

```bash
docker run -d --name deltaflow-postgres -p 5432:5432 -e POSTGRES_DB=deltaflow -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres postgres:16-alpine
docker run -d --name deltaflow-redis -p 6379:6379 redis:7-alpine
```

Start DeltaFlow:

```bash
mvn spring-boot:run
```

---

## Scenario 1: Happy-Path 3-Step Financial Transfer

Send an account-to-account transfer request with a unique `Idempotency-Key`:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: transfer_quickstart_001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 500.00,
    "currency": "USD"
  }'
```

### Expected Response (`201 Created`):

```http
HTTP/1.1 201 Created
Content-Type: application/json
Idempotency-Status: DIRECT

{
  "sagaId": "c4b3f880-9de2-4c6e-8ff4-934d436a0011",
  "transactionId": "tx_transfer_quickstart_001",
  "status": "COMPLETED",
  "amount": 500.00,
  "currency": "USD",
  "stepsCompleted": 3,
  "executedAt": "2026-11-10T06:15:00.123Z"
}
```

---

## Scenario 2: Identical Replay (Zero Downstream Execution)

Re-send the exact same request with the same `Idempotency-Key`:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: transfer_quickstart_001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 500.00,
    "currency": "USD"
  }'
```

### Expected Response (`201 Created`, Served from Cache):

```http
HTTP/1.1 201 Created
Content-Type: application/json
Idempotency-Status: CACHED

{
  "sagaId": "c4b3f880-9de2-4c6e-8ff4-934d436a0011",
  "transactionId": "tx_transfer_quickstart_001",
  "status": "COMPLETED",
  "amount": 500.00,
  "currency": "USD",
  "stepsCompleted": 3,
  "executedAt": "2026-11-10T06:15:00.123Z"
}
```

---

## Scenario 3: Inspecting Saga Instance & WAL Journal Records

### 1. Inspect Top-Level Saga Instance:

```bash
curl -s http://localhost:8080/api/v1/sagas/c4b3f880-9de2-4c6e-8ff4-934d436a0011 | jq .
```

```json
{
  "sagaId": "c4b3f880-9de2-4c6e-8ff4-934d436a0011",
  "tenantId": "default",
  "idempotencyKey": "transfer_quickstart_001",
  "sagaType": "ACCOUNT_TRANSFER",
  "status": "COMPLETED",
  "currentStep": 2,
  "payload": {
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 500.00,
    "currency": "USD"
  }
}
```

### 2. Inspect Step-Level Write-Ahead Log (WAL) Journals:

```bash
curl -s http://localhost:8080/api/v1/sagas/c4b3f880-9de2-4c6e-8ff4-934d436a0011/steps | jq .
```

```json
[
  {
    "stepIndex": 0,
    "stepName": "debit-source-account",
    "status": "COMPLETED",
    "requestPayload": { "accountId": "acc_1001", "amount": 500.00 },
    "responsePayload": { "debitRef": "deb_001", "balanceRemaining": 4500.00 }
  },
  {
    "stepIndex": 1,
    "stepName": "credit-destination-account",
    "status": "COMPLETED",
    "requestPayload": { "accountId": "acc_2002", "amount": 500.00 },
    "responsePayload": { "creditRef": "cred_001", "balanceRemaining": 5500.00 }
  },
  {
    "stepIndex": 2,
    "stepName": "record-ledger-entry",
    "status": "COMPLETED",
    "requestPayload": { "debitRef": "deb_001", "creditRef": "cred_001", "amount": 500.00 },
    "responsePayload": { "ledgerEntryId": "led_001", "status": "POSTED" }
  }
]
```

---

## Scenario 4: Automated Testing

Run the integration suite with Testcontainers:

```bash
mvn test -Dtest=SagaForwardExecutionIntegrationTest -B
```

