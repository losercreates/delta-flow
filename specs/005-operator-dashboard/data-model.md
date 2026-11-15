# Data Model: Operator Management Dashboard

DeltaFlow defines the backend domain transfer models, graph projection representations, and real-time streaming payloads powering the operator dashboard.

---

## 1. Graph Projection Models

### `SagaGraphDto`
Complete visual graph projection of a saga instance for rendering in the interactive DAG visualizer:

| Field | Type | Description |
|---|---|---|
| `sagaId` | `UUID` | Unique saga identifier |
| `sagaType` | `String` | Type name of the saga (e.g. `account-transfer`) |
| `status` | `String` | Global saga lifecycle state (`PENDING`, `EXECUTING`, `COMPLETED`, `COMPENSATING`, `COMPENSATED`, `FAILED`) |
| `tenantId` | `String` | Partition tenant identifier |
| `idempotencyKey` | `String` | Idempotency correlation key |
| `payload` | `String` | Original input payload (JSON) |
| `currentStep` | `int` | Index of the active or failed step |
| `createdAt` | `Instant` | Timestamp when saga started |
| `updatedAt` | `Instant` | Timestamp of last state mutation |
| `nodes` | `List<SagaNodeDto>` | Step and branch nodes for DAG layout |
| `edges` | `List<SagaEdgeDto>` | Directed transition and dependency links |

### `SagaNodeDto`
Visual node representing an individual saga step or parallel branch:

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Unique node key (e.g. `step-0`, `step-1:branch-0`) |
| `stepIndex` | `int` | Sequential step ordinal |
| `stepName` | `String` | Human-readable step name (e.g. `debit-step`, `credit-step`) |
| `status` | `String` | Step state: `NOT_STARTED`, `EXECUTING`, `COMPLETED`, `COMPENSATING`, `COMPENSATED`, `FAILED` |
| `isParallel` | `boolean` | True if this node belongs to a parallel branch group |
| `branchName` | `String` | Name of the branch if parallel, else null |
| `fencingToken` | `Long` | Active monotonic fencing token |
| `requestPayload` | `String` | Step input arguments (JSON) |
| `responsePayload` | `String` | Step output response (JSON) |
| `errorDetails` | `String` | Failure message or stack trace if failed |
| `startedAt` | `Instant` | Timestamp when step began execution |
| `completedAt` | `Instant` | Timestamp when step completed or failed |

### `SagaEdgeDto`
Directed edge connecting nodes in the DAG visualizer:

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Edge identifier (e.g. `edge-step-0-to-step-1`) |
| `source` | `String` | Preceding node ID |
| `target` | `String` | Succeeding node ID |
| `type` | `String` | `FORWARD`, `PARALLEL_FORK`, `PARALLEL_JOIN`, or `COMPENSATION` |
| `animated` | `boolean` | True if connecting edge is currently in-flight |

---

## 2. Operator Intervention Model

### `OperatorInterventionRequest`
Payload submitted by an operator to resolve a stuck saga:

| Field | Type | Constraints | Description |
|---|---|---|---|
| `action` | `String` | NOT NULL, Enum: `FORCE_COMPENSATE`, `FORCE_RETRY` | Desired intervention action |
| `stepIndex` | `Integer` | Nullable | Specific step index to retry (if action is `FORCE_RETRY`) |
| `operatorId` | `String` | NOT NULL, min length 3 | Identity of the authorized operator |
| `justification` | `String` | NOT NULL, min length 10 | Mandatory audit reason for the intervention |

### `OperatorInterventionResult`
Outcome returned after executing an intervention:

| Field | Type | Description |
|---|---|---|
| `sagaId` | `UUID` | Target saga identifier |
| `actionTaken` | `String` | Applied intervention (`FORCE_COMPENSATE` or `FORCE_RETRY`) |
| `previousStatus` | `String` | Saga state prior to intervention |
| `newStatus` | `String` | Resulting saga state after intervention |
| `operatorId` | `String` | Operator identity |
| `auditEventId` | `String` | Correlated `OPERATOR_INTERVENTION` audit event ID |
| `timestamp` | `Instant` | Time of execution |

---

## 3. Forensic & Telemetry Models

### `IdempotencyForensicDto`
Detailed view for auditing idempotency records and verifying payload integrity:

| Field | Type | Description |
|---|---|---|
| `idempotencyKey` | `String` | Client-provided idempotency key |
| `tenantId` | `String` | Tenant identifier |
| `status` | `String` | Current lock state: `PENDING`, `PROCESSING`, `RESOLVED`, `FAILED` |
| `payloadHash` | `String` | Stored SHA-256 digest of original request |
| `cachedResponse` | `String` | Cached JSON response body returned to replayed requests |
| `statusCode` | `Integer` | Cached HTTP response status code |
| `leaseExpiresAt` | `Instant` | Timestamp when current distributed lock lease expires |
| `recoveryAttempts` | `int` | Number of crash recovery claims |
| `createdAt` | `Instant` | Initial request timestamp |
| `updatedAt` | `Instant` | Last update timestamp |

### `TelemetrySummaryDto`
Aggregated system health metrics for the dashboard header:

| Field | Type | Description |
|---|---|---|
| `activeSagas` | `long` | Count of currently executing sagas |
| `completedSagas` | `long` | Lifetime or daily count of completed sagas |
| `compensatedSagas` | `long` | Count of successfully rolled-back sagas |
| `failedSagas` | `long` | Count of halted or failed sagas requiring attention |
| `pendingOutboxEvents` | `long` | Outbox events queued for publishing |
| `deadLetterEvents` | `long` | Quarantined poison pill outbox events |
| `circuitBreakers` | `List<CircuitBreakerStatusDto>` | Active state (`CLOSED`, `HALF_OPEN`, `OPEN`) and failure rates |

---

## 4. Real-Time Streaming Event (`DashboardLiveEvent`)

Broadcast over SSE stream (`GET /api/v1/dashboard/stream`):

```json
{
  "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "eventType": "SAGA_STATE_CHANGED",
  "sagaId": "7d03e7b9-a653-4aeb-a413-911501a24ef0",
  "tenantId": "default",
  "timestamp": "2026-11-15T06:20:00.123Z",
  "payload": {
    "stepIndex": 1,
    "stepName": "credit-step",
    "status": "COMPLETED",
    "previousStatus": "EXECUTING"
  }
}
```

### Event Types
- `SAGA_STARTED`
- `SAGA_STATE_CHANGED`
- `STEP_STATE_CHANGED`
- `SAGA_COMPENSATED`
- `OUTBOX_PUBLISHED`
- `OUTBOX_DEAD_LETTER`
- `CIRCUIT_BREAKER_TRIPPED`
- `OPERATOR_INTERVENTION`
