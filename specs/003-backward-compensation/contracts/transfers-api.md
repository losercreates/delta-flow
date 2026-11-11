# Contract: Money Transfer API (Compensation & Error Response)

## 1. Execute Transfer with Failure & Compensation

### Request

- **Method**: `POST`
- **Path**: `/api/v1/transfers`
- **Headers**:
  - `Content-Type: application/json`
  - `Idempotency-Key: [string, required]` (e.g., `transfer-fail-001`)
  - `X-Tenant-ID: [string, optional, default: 'default']`

```json
{
  "sourceAccountId": "acc_source_100",
  "targetAccountId": "acc_target_200",
  "amount": 500.00,
  "currency": "USD"
}
```

---

### Response: Failure Triggering Automatic Compensation

When downstream execution fails (e.g. simulated credit rejection), forward execution halts and prior completed steps are refunded in reverse order.

- **Status**: `422 Unprocessable Entity`
- **Headers**:
  - `Content-Type: application/json`
  - `Idempotency-Status: DIRECT` (or `CACHED` on subsequent retries)

```json
{
  "sagaId": "4a7371d9-7e72-4638-b765-749e710d0615",
  "idempotencyKey": "transfer-fail-001",
  "status": "COMPENSATED",
  "failedStep": "credit-step",
  "errorMessage": "Target account frozen or credit rejected",
  "compensatedSteps": [
    "debit-step"
  ],
  "timestamp": "2026-11-11T13:00:00Z"
}
```

---

### Response: Idempotent Replay of Compensated Transfer

When a client retries the same `Idempotency-Key` after compensation has settled:

- **Status**: `422 Unprocessable Entity`
- **Headers**:
  - `Content-Type: application/json`
  - `Idempotency-Status: CACHED`

```json
{
  "sagaId": "4a7371d9-7e72-4638-b765-749e710d0615",
  "idempotencyKey": "transfer-fail-001",
  "status": "COMPENSATED",
  "failedStep": "credit-step",
  "errorMessage": "Target account frozen or credit rejected",
  "compensatedSteps": [
    "debit-step"
  ],
  "timestamp": "2026-11-11T13:00:00Z"
}
```

---

## 2. Saga Inspection Endpoint

### Request

- **Method**: `GET`
- **Path**: `/api/v1/sagas/{sagaId}`

### Response

- **Status**: `200 OK`

```json
{
  "sagaId": "4a7371d9-7e72-4638-b765-749e710d0615",
  "tenantId": "default",
  "idempotencyKey": "transfer-fail-001",
  "sagaType": "ACCOUNT_TRANSFER",
  "status": "COMPENSATED",
  "currentStep": 1,
  "steps": [
    {
      "stepIndex": 0,
      "stepName": "debit-step",
      "status": "COMPENSATED",
      "fencingToken": 1,
      "errorDetails": null
    },
    {
      "stepIndex": 1,
      "stepName": "credit-step",
      "status": "FAILED",
      "fencingToken": 1,
      "errorDetails": "Target account frozen or credit rejected"
    },
    {
      "stepIndex": 2,
      "stepName": "ledger-step",
      "status": "NOT_STARTED",
      "fencingToken": 1,
      "errorDetails": null
    }
  ]
}
```

