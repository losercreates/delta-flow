# Quickstart: Operator Management Dashboard

This guide demonstrates end-to-end operational validation of the DeltaFlow Operator Management Dashboard, including live DAG projection, SSE real-time streaming, idempotency forensic querying, and safe manual intervention.

---

## Prerequisites

- Java 21 LTS
- Node.js 18+ (for frontend dashboard development/build)
- Docker / Docker Desktop running (for Testcontainers PostgreSQL 16 & Redis 7)
- Maven 3.9+

---

## Scenario 1: Inspecting Saga Visual DAG Graph

Retrieves the structured node-edge graph projection of an active or completed saga for rendering in the interactive visualizer:

### 1. Execute a Transfer Saga
```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: dag-demo-001" \
  -d '{
    "sourceAccountId": "acc_source_1",
    "targetAccountId": "acc_target_2",
    "amount": 100.00,
    "currency": "USD"
  }'
```

### 2. Query Saga Visual Graph Projection
```bash
# Replace SAGA_ID with the sagaId from step 1 response
curl -i http://localhost:8080/api/v1/dashboard/sagas/{SAGA_ID}/graph
```

**Expected Response**:
```json
{
  "sagaId": "...",
  "sagaType": "account-transfer",
  "status": "COMPLETED",
  "nodes": [
    {
      "id": "step-0",
      "stepIndex": 0,
      "stepName": "debit-step",
      "status": "COMPLETED"
    },
    {
      "id": "step-1",
      "stepIndex": 1,
      "stepName": "credit-step",
      "status": "COMPLETED"
    },
    {
      "id": "step-2",
      "stepIndex": 2,
      "stepName": "ledger-step",
      "status": "COMPLETED"
    }
  ],
  "edges": [
    {"id": "e0-1", "source": "step-0", "target": "step-1", "type": "FORWARD"},
    {"id": "e1-2", "source": "step-1", "target": "step-2", "type": "FORWARD"}
  ]
}
```

---

## Scenario 2: Real-Time Event Streaming via Server-Sent Events (SSE)

Subscribe to real-time events broadcast by the orchestrator:

```bash
curl -N http://localhost:8080/api/v1/dashboard/stream
```

**Expected Stream Event**:
```text
data: {"eventType":"SAGA_STATE_CHANGED","sagaId":"...","payload":{"stepName":"credit-step","status":"COMPLETED"}}
```

---

## Scenario 3: Idempotency Forensics & Tampering Audit

Verify request hashes and detect duplicate or tampered requests:

```bash
# Query by idempotency key
curl -i "http://localhost:8080/api/v1/dashboard/idempotency?key=dag-demo-001"
```

**Expected Response**:
```json
[
  {
    "idempotencyKey": "dag-demo-001",
    "status": "RESOLVED",
    "payloadHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "cachedResponse": "{\"transferId\":\"...\",\"status\":\"COMPLETED\"}",
    "statusCode": 201
  }
]
```

---

## Scenario 4: Safe Manual Intervention on a Stuck Saga

When a saga is halted due to a permanent downstream service failure and its lease has expired, safely force-settle it into `COMPENSATED` state:

```bash
curl -i -X POST http://localhost:8080/api/v1/dashboard/sagas/{SAGA_ID}/intervene \
  -H "Content-Type: application/json" \
  -d '{
    "action": "FORCE_COMPENSATE",
    "operatorId": "operator_jane",
    "justification": "Downstream banking partner down for scheduled maintenance; manual refund validated with customer"
  }'
```

**Expected Response**:
```json
{
  "sagaId": "...",
  "actionTaken": "FORCE_COMPENSATE",
  "previousStatus": "FAILED",
  "newStatus": "COMPENSATED",
  "operatorId": "operator_jane",
  "auditEventId": "..."
}
```

---

## Scenario 5: System Telemetry & Circuit Breaker Health

Inspect active sagas, dead letter queues, and Resilience4j circuit breakers:

```bash
curl -i http://localhost:8080/api/v1/dashboard/telemetry
```

**Expected Response**:
```json
{
  "activeSagas": 0,
  "completedSagas": 14,
  "compensatedSagas": 2,
  "failedSagas": 0,
  "pendingOutboxEvents": 0,
  "deadLetterEvents": 0,
  "circuitBreakers": [
    {
      "name": "creditService",
      "state": "CLOSED",
      "failureRate": 0.0
    }
  ]
}
```
