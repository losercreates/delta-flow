# Phase 1: Data Model & State Transitions: High Availability, Distributed Leader Coordination & Storage Tiering

## 1. Database Schema Migrations

### Flyway Migration `V6__outbox_lease_and_clustering.sql`

```sql
-- 1. Extend outbox_events for lease expiration and worker claiming
ALTER TABLE outbox_events 
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS claimed_by_worker VARCHAR(128);

-- 2. Partial index for sub-millisecond in-flight lease recovery scans
CREATE INDEX IF NOT EXISTS idx_outbox_events_inflight_lease 
    ON outbox_events (status, lease_expires_at) 
    WHERE status = 'IN_FLIGHT';

-- 3. Partial index for high-performance idempotency retention pruning
CREATE INDEX IF NOT EXISTS idx_idempotency_retention 
    ON idempotency_records (status, created_at) 
    WHERE status = 'RESOLVED';
```

---

## 2. Domain Entities & DTO Structures

### 2.1 Cluster Topology DTOs

#### `ClusterNodeDto`
Represents an individual application instance in the cluster.
```typescript
interface ClusterNodeDto {
  nodeId: string;
  hostname: string;
  port: number;
  role: 'LEADER' | 'STANDBY';
  startedAt: string;
  lastHeartbeatAt: string;
  isHealthy: boolean;
  version: string;
}
```

#### `LeaderStatusDto`
Represents the current leader lease state in Redis.
```typescript
interface LeaderStatusDto {
  leaderNodeId: string;
  isCurrentNodeLeader: boolean;
  leaseAcquiredAt: string;
  leaseExpiresAt: string;
  leaseDurationMs: number;
}
```

---

### 2.2 Data Retention DTOs

#### `RetentionSummaryDto`
Represents the status and statistics of the idempotency data retention cleaner.
```typescript
interface RetentionSummaryDto {
  enabled: boolean;
  retentionDays: number;
  batchSize: number;
  lastRunAt: string | null;
  totalPrunedRecords: number;
  oldestRemainingResolvedRecord: string | null;
}
```

#### `RetentionTriggerRequest`
Payload submitted by operators or schedulers to execute an on-demand retention sweep.
```typescript
interface RetentionTriggerRequest {
  retentionDays?: number;
  batchSize?: number;
  operatorId: string;
  justification: string;
}
```

---

### 2.3 In-Flight Outbox Lease Recovery Structures

#### `OutboxLeaseReclaimResult`
Summary of an in-flight outbox lease reclamation sweep.
```typescript
interface OutboxLeaseReclaimResult {
  scannedCount: number;
  reclaimedCount: number;
  deadLetteredCount: number;
  timestamp: string;
}
```

---

## 3. State Transitions & Distributed Coordination Lifecycles

### 3.1 In-Flight Outbox Lease Lifecycle
```
[OUTBOX: PENDING]
       │
       ▼ (Worker claims batch with lease_expires_at = now + 60s)
[OUTBOX: IN_FLIGHT] ──(Worker crash / timeout: lease_expires_at < now)──┐
       │                                                                │
       │ (Normal completion)                                            ▼ (Recovery Reaper)
       ▼                                                 [Retry Count < max_retries?]
[OUTBOX: PUBLISHED]                                      ├── YES ──► [OUTBOX: PENDING] (re-queued)
                                                         └── NO  ──► [OUTBOX: DEAD_LETTER]
```

### 3.2 Distributed Leader Election & Failover Lifecycle
```
[NODE STARTUP]
       │
       ▼ (Attempt atomic SET NX PX on deltaflow:cluster:leader)
[ACQUIRED LEASE?]
       ├── YES ──► [ROLE: LEADER]
       │                 │
       │                 ├──► Renew lease every 1.5s
       │                 ├──► Trigger background jobs (Recovery Scanner, Outbox, Retention)
       │                 └──► (On shutdown / network isolation): Step down, release key
       │
       └── NO  ──► [ROLE: STANDBY]
                         │
                         ├──► Register heartbeat in cluster map
                         ├──► Stand by without executing duplicate background scans
                         └──► (If leader key expires): Immediately contest election
```
