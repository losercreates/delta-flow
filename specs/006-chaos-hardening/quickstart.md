# Quickstart: Chaos Testing, OpenTelemetry & Production Hardening

This guide demonstrates end-to-end validation of the DeltaFlow resilience and production hardening capabilities: running Toxiproxy network chaos injection tests, certifying financial invariant integrity, tracing transactions via W3C `traceparent`, and exporting tamper-evident SOC2/PCI-DSS audit logs.

---

## Prerequisites

- Java 21 LTS
- Docker / Docker Desktop running (for Testcontainers PostgreSQL 16, Redis 7, and Toxiproxy)
- Maven 3.9+
- `curl` and `jq` CLI utilities

---

## Scenario 1: Running Toxiproxy Network Chaos Test Suite

Executes the automated network chaos suite simulating 30% packet drops and 500ms latency spikes against persistence and distributed lock layers:

### Run Chaos Test Suite
```bash
mvn test -Dtest=ToxiproxyNetworkChaosIntegrationTest
```

**Expected Result**:
- Testcontainers provisions PostgreSQL, Redis, and a Toxiproxy TCP proxy container.
- High-concurrency money transfers are dispatched while Toxiproxy dynamically applies:
  - `packet_loss`: 30% downstream dropped packets.
  - `latency`: 500ms added latency with 150ms jitter.
- Workflows either complete forward execution or cleanly roll back via backward compensation.
- Build output confirms:
  ```text
  [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  ```

---

## Scenario 2: Automated Financial Invariant Verification

Audits all simulated accounts and journal records to verify that total debits strictly equal total credits plus refunds:

### Trigger Invariant Audit
```bash
curl -i http://localhost:8080/api/v1/invariants/verify
```

**Expected Response**:
```json
{
  "reportId": "d82f7140-5e82-411a-8c6e-71649931b261",
  "status": "PASSED",
  "totalTransactionsAudited": 1000,
  "successfulTransactions": 924,
  "compensatedTransactions": 76,
  "failedTransactions": 0,
  "totalSourceDebits": 250000.00,
  "totalTargetCredits": 231000.00,
  "totalRefundedCredits": 19000.00,
  "discrepancyAmount": 0.00,
  "doubleSpendCount": 0,
  "orphanedDebitCount": 0,
  "violations": []
}
```

---

## Scenario 3: W3C `traceparent` Distributed Tracing

Dispatches a money transfer carrying an external W3C trace context header and validates trace continuity:

### Submit Transfer with W3C Trace Header
```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: trace-demo-001" \
  -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  -d '{
    "sourceAccountId": "acc_source_1",
    "targetAccountId": "acc_target_2",
    "amount": 100.00,
    "currency": "USD"
  }'
```

**Validation**:
- Inspect response headers or OpenTelemetry spans.
- The returned response contains the corresponding trace identifier:
  `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-...-01`.
- Step execution spans in database journal and outbox domain events preserve the originating trace ID `4bf92f3577b34da6a3ce929d0e0e4736`.

---

## Scenario 4: Exporting SOC2/PCI-DSS Immutable Audit Trail

Streams tamper-evident audit logs with chained SHA-256 integrity hashes and masked account numbers:

### 1. Export in NDJSON Format
```bash
curl -s "http://localhost:8080/api/v1/audit/export?format=JSON" | jq .
```

**Expected Entry**:
```json
{
  "sequenceNumber": 1,
  "eventId": "f74a010b-58cc-4372-a567-0e02b2c3d479",
  "eventType": "SAGA_STARTED",
  "tenantId": "default",
  "idempotencyKey": "trace-demo-001",
  "timestamp": "2026-11-16T12:00:00.123Z",
  "maskedPayload": "{\"sourceAccount\":\"****-****-****-1002\",\"amount\":100.00}",
  "previousEventHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "recordChecksum": "c3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2"
}
```

### 2. Export in CSV Format
```bash
curl -s "http://localhost:8080/api/v1/audit/export?format=CSV" | head -n 5
```

---

## Scenario 5: Validating Kubernetes Readiness & Liveness Probes

Verifies health endpoints during steady-state and graceful termination:

### Query Probes
```bash
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness
```

**Expected Response**:
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "circuitBreakers": { "status": "UP" }
  }
}
```
