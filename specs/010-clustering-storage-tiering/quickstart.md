# Quickstart & Verification Guide: High Availability & Distributed Clustering

This guide documents the procedures for verifying in-flight outbox lease recovery, distributed leader election, idempotency retention pruning, and cluster topology telemetry.

---

## Prerequisites & Setup

1. **Running Infrastructure**:
   - PostgreSQL 16 (Port 5432)
   - Redis 7 (Port 6379)
   - Redpanda / Kafka (Port 9092)
   - Toxiproxy (Port 8474)

2. **Backend Startup**:
   ```bash
   mvn clean spring-boot:run
   ```

3. **Frontend Startup**:
   ```bash
   cd frontend
   npm run dev
   ```
   Open `http://localhost:5173`.

---

## Verification Scenarios

### Scenario 1: In-Flight Outbox Lease Recovery After Worker Crash
1. **Action**: Seed an outbox event in `outbox_events` with status `IN_FLIGHT` and an expired lease timestamp:
   ```sql
   INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, lease_expires_at, created_at)
   VALUES (gen_random_uuid(), 'SAGA_INSTANCE', gen_random_uuid()::text, 'SAGA_COMPLETED', '{"status":"COMPLETED"}', 'IN_FLIGHT', 0, NOW() - INTERVAL '2 minutes', NOW() - INTERVAL '3 minutes');
   ```
2. **Trigger Reaper**:
   - Allow the scheduled `OutboxLeaseRecoveryReaper` to scan, or trigger manually via REST:
     ```bash
     curl -X POST http://localhost:8080/api/v1/cluster/outbox/reclaim-leases
     ```
3. **Verify**:
   - The orphaned event status transitions from `IN_FLIGHT` back to `PENDING`.
   - `retry_count` is incremented from 0 to 1.
   - The subsequent `OutboxPublisher` dispatch sweep claims and publishes the event to Kafka.

---

### Scenario 2: Multi-Node Leader Election & Failover Under 5 Seconds
1. **Action**: Start two application instances (`Node A` and `Node B`) connected to the same Redis instance:
   ```bash
   # Instance A (Leader)
   SERVER_PORT=8080 DELTAFLOW_CLUSTER_NODE_ID=node-alpha mvn spring-boot:run

   # Instance B (Standby)
   SERVER_PORT=8081 DELTAFLOW_CLUSTER_NODE_ID=node-beta mvn spring-boot:run
   ```
2. **Verify Leader**:
   - Query `GET http://localhost:8080/api/v1/cluster/leader`.
   - Observe that `node-alpha` holds the leadership lease.
   - Background tasks run exclusively on `node-alpha`.
3. **Trigger Failover**:
   - Abruptly terminate `Node A` (`kill -9 <PID>`).
   - Monitor `Node B` logs or poll `GET http://localhost:8081/api/v1/cluster/leader`.
4. **Verify**:
   - Within 5 seconds, `node-beta` acquires the leadership lease.
   - `LEADER_ELECTED` is broadcast over the live SSE stream.
   - `Node B` assumes coordinator responsibilities for recovery scanning and outbox polling.

---

### Scenario 3: Automated Idempotency Retention Pruning
1. **Action**: Seed resolved idempotency records older than 30 days alongside in-flight `PROCESSING` records:
   ```sql
   -- Expired resolved record (should be pruned)
   INSERT INTO idempotency_records (tenant_id, idempotency_key, request_hash, status, lease_expires_at, created_at, updated_at)
   VALUES ('test-tenant', 'key-old-resolved', 'hash-1', 'RESOLVED', NOW() - INTERVAL '35 days', NOW() - INTERVAL '35 days', NOW() - INTERVAL '35 days');

   -- Active in-flight record (MUST NOT be pruned)
   INSERT INTO idempotency_records (tenant_id, idempotency_key, request_hash, status, lease_expires_at, created_at, updated_at)
   VALUES ('test-tenant', 'key-active-lock', 'hash-2', 'PROCESSING', NOW() + INTERVAL '30 seconds', NOW(), NOW());
   ```
2. **Trigger Pruning**:
   ```bash
   curl -X POST http://localhost:8080/api/v1/cluster/retention/trigger \
     -H "Content-Type: application/json" \
     -d '{"retentionDays":30,"batchSize":500,"operatorId":"ops-lead","justification":"Manual retention prune verification"}'
   ```
3. **Verify**:
   - `key-old-resolved` is deleted.
   - `key-active-lock` remains in the database intact.
   - Total pruned records counter increments in `GET /api/v1/cluster/retention/status`.

---

### Scenario 4: Live Cluster Topology in Operator Dashboard
1. **Action**: In the Operator Dashboard, navigate to the **Cluster Topology** section.
2. **Verify**:
   - Active nodes appear with node ID, IP/hostname, and role badge (Leader vs. Standby).
   - Heartbeat age updates live.
   - Data retention statistics display total pruned records and oldest retained transaction date.

---

## Automated Integration Test Execution

Run the complete high-availability and cluster integration test suite:
```bash
mvn test -Dtest=*Cluster*,*LeaseRecovery*,*Retention*
```
All tests must execute against real Testcontainers and pass 100% green.
