# Quickstart & Validation Guide: Production Packaging & Observability

**Feature Branch**: `007-production-packaging`  
**Date**: 2026-11-17  
**Spec Reference**: [specs/007-production-packaging/spec.md](spec.md)

This guide documents five runnable, end-to-end validation scenarios proving that the production multi-container stack, OpenTelemetry tracing, Prometheus scraping, and Grafana dashboards work out-of-the-box.

---

## Prerequisites

- **Docker**: Engine 20.10+
- **Docker Compose**: Compose v2 (`docker compose` CLI)
- **Host Tools**: `curl`, `jq`, `make` (optional)

---

## Scenario 1: One-Command Stack Startup & Health Verification

Validate that all seven services initialize in deterministic order and transition to `healthy` status.

### 1.1 Launch Stack
```bash
# Bring up entire stack in background (or: make up)
docker compose up -d
```

### 1.2 Verify Container Health Status
```bash
# Inspect container health states
docker compose ps
```
**Expected Outcome**:
All seven containers (`deltaflow-postgres`, `deltaflow-redis`, `deltaflow-backend`, `deltaflow-frontend`, `deltaflow-jaeger`, `deltaflow-prometheus`, `deltaflow-grafana`) are in `Up (healthy)` or `Up` status within 60 seconds.

### 1.3 Verify Backend Readiness Probe
```bash
curl -s http://localhost:8080/actuator/health/readiness | jq .
```
**Expected Response**:
```json
{
  "status": "UP"
}
```

---

## Scenario 2: Financial Transfer & Live Distributed Trace Inspection (Jaeger)

Validate that executed transactions automatically emit W3C traces to Jaeger via OTLP.

### 2.1 Submit Financial Transfer
```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: prod_pkg_tx_1001" \
  -d '{
    "sourceAccountId": "acc_alpha_101",
    "targetAccountId": "acc_beta_202",
    "amount": 750.00,
    "currency": "USD"
  }'
```
**Expected Response**: `HTTP 201 Created` with header `Idempotency-Status: DIRECT`.

### 2.2 Inspect W3C Trace in Jaeger UI
1. Open browser to **`http://localhost:16686`**.
2. Select Service: **`delta-flow`**.
3. Click **Find Traces**.
4. Click on the latest trace corresponding to `prod_pkg_tx_1001`.

**Expected Trace Hierarchy**:
```text
▼ POST /api/v1/transfers [HTTP SERVER]
   ├─► idempotency-gate: acquire-lock [INTERNAL]
   ├─► saga: account-transfer [INTERNAL]
   │    ├─► saga-step: debit-step [INTERNAL, thread: virtual-N]
   │    ├─► saga-step: credit-step [INTERNAL, thread: virtual-N]
   │    └─► saga-step: ledger-step [INTERNAL, thread: virtual-N]
   └─► outbox: record-event [INTERNAL]
```

---

## Scenario 3: Real-Time Metrics & Pre-Provisioned Grafana Dashboard

Validate that custom domain metrics are scraped by Prometheus and rendered on the pre-provisioned Grafana dashboard.

### 3.1 Verify Prometheus Metrics Scrape
```bash
# Query Prometheus for total idempotency requests
curl -s "http://localhost:9090/api/v1/query?query=deltaflow_idempotency_requests_total" | jq '.data.result'
```
**Expected Response**:
JSON array showing `deltaflow_idempotency_requests_total{status="direct"}` with value $\ge 1$.

### 3.2 Access Grafana Dashboard
1. Open browser to **`http://localhost:3001`**.
2. Navigate to **Dashboards** > **DeltaFlow Operational Reliability**.
3. View the live pre-configured panels:
   - **Throughput & Outcomes**: Total requests partitioned by `direct`, `cached`, `conflict`.
   - **Saga Latency Percentiles**: p50, p95, and p99 duration curves.
   - **Distributed Lock Acquisition Time**: Redis vs PostgreSQL wait times.
   - **Active Steps & Compensation Rate**: Current in-flight virtual threads.

---

## Scenario 4: Automated Turnkey Smoke Test Execution

Execute the comprehensive automated test suite verifying all services, scenarios, metrics, and traces.

### 4.1 Run Smoke Script
```bash
chmod +x ./scripts/smoke-test.sh
./scripts/smoke-test.sh
```
*(Alternatively: `make smoke`)*

**Expected Terminal Output**:
```text
============================================================
           DeltaFlow Production Smoke Verification          
============================================================
[1/6] Checking container health states...              [PASS]
[2/6] Validating Scenario 1 (Direct Payment)...        [PASS] (201 Created, DIRECT)
[3/6] Validating Scenario 2 (Idempotent Replay)...     [PASS] (201 Created, CACHED, 8ms)
[4/6] Validating Scenario 3 (Payload Tampering)...     [PASS] (409 Conflict, MISMATCH)
[5/6] Validating Scenario 4 (Compensation Rollback)... [PASS] (COMPENSATED)
[6/6] Verifying Prometheus Metrics & Jaeger Traces...  [PASS]
============================================================
RESULT: ALL 6 CHECKS PASSED (Total Elapsed: 8.4s)
============================================================
```

---

## Scenario 5: Graceful Shutdown & Volume Persistence Verification

Validate that stopping and restarting the stack preserves state and drains in-flight work cleanly.

### 5.1 Graceful Teardown
```bash
docker compose down
```
**Expected Outcome**: Containers receive `SIGTERM`, backend drains within the 30-second shutdown phase, and containers stop without forced `SIGKILL`.

### 5.2 Restart & Persistence Check
```bash
docker compose up -d
```
Query the historical transfer executed in Scenario 2:
```bash
curl -s http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: prod_pkg_tx_1001" \
  -d '{
    "sourceAccountId": "acc_alpha_101",
    "targetAccountId": "acc_beta_202",
    "amount": 750.00,
    "currency": "USD"
  }' | jq .
```
**Expected Response**: `HTTP 201 Created` with `Idempotency-Status: CACHED` and the original transaction details preserved from PostgreSQL and Redis named volumes.
