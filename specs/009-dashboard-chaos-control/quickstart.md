# Quickstart & Verification Guide: Real-Time Streaming & Chaos Control

This guide documents the procedures for testing and verifying real-time streaming, chaos control, financial invariant verification, and DLQ replay in the DeltaFlow Operator Dashboard.

---

## Prerequisites & Setup

1. **Running Infrastructure**:
   - PostgreSQL 16 (Port 5432)
   - Redis 7 (Port 6379)
   - Redpanda / Kafka (Port 9092)
   - Toxiproxy (Port 8474) [Provided automatically in Testcontainers during tests]

2. **Backend Startup**:
   ```bash
   mvn clean spring-boot:run
   ```

3. **Frontend Startup**:
   ```bash
   cd frontend
   npm install
   npm run dev
   ```
   Open `http://localhost:5173` in a web browser.

---

## Verification Scenarios

### Scenario 1: Live Event Streaming & Dynamic DAG Progression
1. **Action**: Open the Operator Dashboard at `http://localhost:5173`. Ensure the header displays the green `LIVE STREAM` indicator badge.
2. **Execute Saga**: Submit a multi-step financial payment saga via REST API:
   ```bash
   curl -X POST http://localhost:8080/api/v1/sagas/transfers \
     -H "Content-Type: application/json" \
     -H "Idempotency-Key: test-live-stream-001" \
     -d '{"tenantId":"bank-alpha","fromAccount":"acc-101","toAccount":"acc-202","amount":250.00}'
   ```
3. **Verify**:
   - The saga appears in the left sidebar list immediately without refreshing the page.
   - The visual DAG updates each step node (Debit -> Credit -> Ledger) from gray/pending to pulsating blue (executing) to green (completed).
   - Telemetry counters (Active, Completed, Total) increment in real time.

---

### Scenario 2: Chaos Fault Injection & Automatic Circuit Breaker Tripping
1. **Action**: Navigate to the **Chaos & Invariants** tab in the dashboard.
2. **Inject Fault**:
   - Under **Chaos Fault Injector**, select target `postgres-proxy` or `payment-gateway`.
   - Set Latency to `500ms` and Packet Loss to `30%`.
   - Click **Inject Fault**.
3. **Verify**:
   - The active toxic badge appears with amber warning color.
   - Live stream broadcasts `CHAOS_INJECTED`.
   - Subsequent transactions experience delayed execution and trigger Resilience4j circuit breaker state transitions (`CLOSED` -> `OPEN`).
4. **Heal Fault**:
   - Click **Heal / Reset All Chaos**.
   - Verify that all toxics are removed, the status returns to healthy, and `CHAOS_HEALED` is broadcast.

---

### Scenario 3: On-Demand Financial Invariant Audit
1. **Action**: In the **Chaos & Invariants** tab, locate the **Financial Invariant Verifier** section.
2. **Execute Audit**: Click **Run Invariant Audit** (optionally provide Tenant ID `bank-alpha`).
3. **Verify**:
   - The system scans all `saga_instances`, `saga_steps`, and `idempotency_records`.
   - Report renders within 3 seconds displaying:
     - Total Audited Transactions
     - Total Debits == Total Credits + Total Refunds
     - Zero Double-Spends (Count: 0)
     - Zero Orphaned Debits (Count: 0)
     - Status: **PASSED (Green Badge)**.

---

### Scenario 4: Dead-Letter Queue (DLQ) Forensics & Manual Replay
1. **Action**: Induce a broker failure by stopping Kafka or misconfiguring topic routing so an outbox event exceeds maximum retry attempts and moves to `DEAD_LETTER`.
2. **Inspect DLQ**: Open the **Dead-Letter Queue** table in the dashboard.
3. **Replay Event**:
   - Click **Replay** on the poisoned event.
   - Enter operator ID (e.g. `ops-lead-01`) and justification (`"Broker connection restored; replaying poison event"`).
4. **Verify**:
   - Status updates from `DEAD_LETTER` to `PENDING`.
   - The outbox publisher immediately sweeps and publishes the replayed event.
   - Event disappears from the DLQ view.

---

## Automated Integration Test Execution

Run the complete chaos and dashboard test suite:
```bash
mvn test -Dtest=*Chaos*,*Dashboard*,*Invariant*
```
All tests must execute against real Testcontainers and pass 100% green.
