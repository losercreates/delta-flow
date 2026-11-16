# Data Model: Chaos Testing, OpenTelemetry & Production Hardening

DeltaFlow defines domain models, report projections, tracing wrappers, and cryptographic audit structures for chaos testing, financial invariant verification, and regulatory audit exports.

---

## 1. Financial Invariant Verification Models

### `InvariantVerificationReport`
Represents the cryptographically certified outcome of an invariant verification run across simulated accounts and transaction ledgers:

| Field | Type | Description |
|---|---|---|
| `reportId` | `UUID` | Unique identifier of the verification report |
| `timestamp` | `Instant` | Time when verification completed |
| `status` | `String` | `PASSED` or `VIOLATED` |
| `totalTransactionsAudited` | `long` | Total number of saga transactions evaluated |
| `successfulTransactions` | `long` | Transactions that completed forward execution |
| `compensatedTransactions` | `long` | Transactions that rolled back cleanly via compensation |
| `failedTransactions` | `long` | Transactions that halted or required operator intervention |
| `totalSourceDebits` | `BigDecimal` | Sum of all debits deducted from source accounts |
| `totalTargetCredits` | `BigDecimal` | Sum of all credits deposited into target accounts |
| `totalRefundedCredits` | `BigDecimal` | Sum of all compensation refunds returned to source accounts |
| `discrepancyAmount` | `BigDecimal` | Exact financial disparity: `Debits - (Credits + Refunds)`. MUST be 0.00. |
| `doubleSpendCount` | `int` | Number of duplicate debit/credit executions detected. MUST be 0. |
| `orphanedDebitCount` | `int` | Number of uncredited and unrefunded debits detected. MUST be 0. |
| `violations` | `List<InvariantViolation>` | Details of any balance or state invariant violations found |

### `AccountBalanceSnapshot`
Snapshot of an account balance before and after a high-concurrency chaos workload:

| Field | Type | Description |
|---|---|---|
| `accountId` | `String` | Account identifier |
| `initialBalance` | `BigDecimal` | Balance prior to test execution |
| `currentBalance` | `BigDecimal` | Balance after test execution |
| `totalDebited` | `BigDecimal` | Cumulative outgoing transfers |
| `totalCredited` | `BigDecimal` | Cumulative incoming transfers |
| `totalRefunded` | `BigDecimal` | Cumulative compensation refunds received |
| `isConsistent` | `boolean` | `true` if `initialBalance - totalDebited + totalCredited + totalRefunded == currentBalance` |

### `InvariantViolation`
Specific invariant inconsistency detected during verification:

| Field | Type | Description |
|---|---|---|
| `violationType` | `String` | `DOUBLE_SPEND`, `ORPHANED_DEBIT`, `BALANCE_MISMATCH`, `DUPLICATE_IDEMPOTENCY` |
| `accountId` | `String` | Related account identifier (if applicable) |
| `sagaId` | `UUID` | Related saga identifier (if applicable) |
| `expectedValue` | `String` | Expected financial or state invariant value |
| `actualValue` | `String` | Observed inconsistent value |
| `details` | `String` | Forensic description of the inconsistency |

---

## 2. Distributed Tracing Models

### `TraceContextMetadata`
Immutable record carrying W3C Trace Context across boundaries:

| Field | Type | Constraints | Description |
|---|---|---|---|
| `traceId` | `String` | 32 hex chars | W3C 16-byte global trace identifier |
| `spanId` | `String` | 16 hex chars | W3C 8-byte current span identifier |
| `parentSpanId` | `String` | 16 hex chars, nullable | Immediate parent span identifier |
| `traceFlags` | `String` | 2 hex chars | W3C sampling flags (e.g. `01` for sampled) |
| `traceparent` | `String` | 55 chars format: `00-{traceId}-{spanId}-{traceFlags}` | Full W3C header value |

---

## 3. Immutable Audit Trail & Regulatory Export Models

### `AuditExportRecord`
Normalized, PCI-DSS compliant record formatted for SOC2/PCI-DSS regulatory export:

| Field | Type | Description |
|---|---|---|
| `sequenceNumber` | `long` | Monotonic sequential audit event counter |
| `eventId` | `UUID` | Unique event identifier |
| `eventType` | `String` | E.g. `SAGA_STARTED`, `STEP_COMPLETED`, `STEP_COMPENSATED`, `OPERATOR_INTERVENTION` |
| `tenantId` | `String` | Partition tenant identifier |
| `idempotencyKey` | `String` | Client idempotency correlation key |
| `sagaId` | `String` | Associated saga instance identifier (if applicable) |
| `timestamp` | `Instant` | Event occurrence timestamp |
| `operatorId` | `String` | Operator identifier (for manual interventions, else null) |
| `maskedPayload` | `String` | PCI-DSS sanitized JSON payload (PAN masked, tokens stripped) |
| `payloadHash` | `String` | SHA-256 digest of the original request/event |
| `previousEventHash`| `String` | SHA-256 hash of preceding audit record in the chain ($H_{i-1}$) |
| `recordChecksum` | `String` | Chained cryptographic digest: $\text{SHA-256}(H_{i-1} \parallel \text{recordBytes})$ |

### `AuditExportRequest`
Query parameters for filtering audit trail exports:

| Field | Type | Constraints | Description |
|---|---|---|---|
| `fromTimestamp` | `Instant` | Optional | Start timestamp for audit window |
| `toTimestamp` | `Instant` | Optional | End timestamp for audit window |
| `tenantId` | `String` | Optional | Filter by tenant |
| `eventType` | `String` | Optional | Filter by event type |
| `format` | `String` | Default: `JSON`, Enum: `JSON`, `CSV` | Serialization format |

---

## 4. Production Health & Hardening Models

### `ProductionHealthDetails`
Composite health status payload reflecting operational readiness:

| Field | Type | Description |
|---|---|---|
| `status` | `String` | `UP` or `DOWN` / `OUT_OF_SERVICE` |
| `databaseStatus` | `String` | PostgreSQL connectivity and query response time |
| `redisStatus` | `String` | Redis distributed lock and lease latency |
| `circuitBreakers` | `Map<String, String>` | Map of circuit breaker names to states (`CLOSED`, `HALF_OPEN`, `OPEN`) |
| `activeSagaCount` | `long` | Number of currently executing transactions |
| `draining` | `boolean` | `true` if node is currently in graceful termination draining phase |
| `uptimeSeconds` | `long` | Process uptime in seconds |
