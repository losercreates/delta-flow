# Phase 0: Research & Technical Decisions: High Availability, Distributed Leader Coordination & Storage Tiering

## Decision 1: In-Flight Outbox Lease Tracking & Recovery Architecture

- **Decision**: Extend `outbox_events` with `lease_expires_at` and `claimed_by_worker` columns, and implement a dedicated `OutboxLeaseRecoveryReaper` in `OutboxPublisher` that reclaims expired `IN_FLIGHT` events.
- **Rationale**:
  - In high-throughput event outbox architectures, claiming events into `IN_FLIGHT` via `SELECT ... FOR UPDATE SKIP LOCKED` avoids holding long database transactions across network dispatch calls.
  - However, if the worker JVM abruptly terminates (OOM, SIGKILL, pod eviction) while an event is `IN_FLIGHT`, the event would otherwise remain stranded forever.
  - Adding an explicit `lease_expires_at` timestamp (default: `now + 60s`) allows a background sweep to safely identify abandoned in-flight events.
  - Reclaiming resets `status = 'PENDING'`, increments `retry_count`, and emits an `OUTBOX_LEASE_RECLAIMED` audit event. If `retry_count >= maxRetries`, it routes directly to `DEAD_LETTER` to prevent poison message head-of-line blocking.
- **Alternatives Considered**:
  - *Keep database transaction open during Kafka dispatch*: Rejected because distributed network latency holding database row locks degrades database connection pool concurrency and violates Constitution Principle I (WAL pattern separation).
  - *Manual operator DLQ intervention only*: Rejected because transient worker restarts should self-heal automatically without human pager alerts.

---

## Decision 2: Distributed Leader Coordination (Redis/Redisson vs. ShedLock)

- **Decision**: Implement `ClusterLeaderElectionService` using DeltaFlow's existing `RedissonClient` with atomic lease expiration (`SET NX PX`) and periodic heartbeat renewal.
- **Rationale**:
  - DeltaFlow already includes Redisson 3.37.0 connected to Redis 7 as its distributed lock manager.
  - Implementing leader election via Redis atomic keys (`deltaflow:cluster:leader`) provides sub-second failover detection without adding external dependencies or heavy Zookeeper/Etcd infrastructure.
  - The elected leader holds a 5-second lease renewed every 1.5 seconds. If the leader crashes or partitions, the lease expires within 5 seconds, and a standby node immediately acquires leadership.
  - Background workers (`SagaRecoveryScanner`, `OutboxPublisher`, data retention cleaners) query `ClusterLeaderElectionService.isLeader()` before running, guaranteeing that only the active leader triggers cluster-wide scans while follower nodes remain in warm standby.
  - Each node registers its presence in `deltaflow:cluster:nodes` with a 15-second TTL, refreshed via periodic heartbeats.
- **Alternatives Considered**:
  - *ShedLock with JDBC table*: Slower failover times (typically 30-60s) and generates continuous database write load on PostgreSQL.
  - *Raft / JGroups clustering*: Substantial operational complexity and memory footprint compared to leveraging the existing Redis infrastructure.

---

## Decision 3: Idempotency Record Retention & Safe Batch Pruning

- **Decision**: Implement `IdempotencyRetentionService` executing bounded batch purges (`DELETE FROM idempotency_records WHERE status = 'RESOLVED' AND created_at < :cutoff LIMIT :batchSize`) on a scheduled basis.
- **Rationale**:
  - High-throughput financial gateways accumulate millions of idempotency records. Without retention lifecycle management, index size grows indefinitely, degrading B-Tree lookup performance.
  - Terminal records (`RESOLVED`) older than the retention SLA (default: 30 days) are safe to prune; historical accounting ledgers and immutable audit logs remain permanently stored in `audit_logs` and `transfer_records`.
  - Pruning MUST strictly filter for `status = 'RESOLVED'`; records in `PROCESSING`, `PENDING`, or `FAILED` are never touched, ensuring active lock leases are protected 100%.
  - Executing deletion in bounded batches (default: 500 rows per transaction) with backoff prevents table lock escalation, transaction log bloat, and replication lag on read replicas.
- **Alternatives Considered**:
  - *PostgreSQL Partition Dropping*: Dropping partitions is very fast, but requires partitioning by date from day 1 and does not allow selective retention of active vs resolved keys within the same date partition.
  - *No pruning / infinite storage*: Degrades lookup latency over time and inflates backup sizes.

---

## Decision 4: PostgreSQL Index Optimization for In-Flight Leases and Retention

- **Decision**: Add targeted partial indexes in Flyway migration `V6__outbox_lease_and_clustering.sql`:
  - `CREATE INDEX idx_outbox_events_inflight_lease ON outbox_events (status, lease_expires_at) WHERE status = 'IN_FLIGHT';`
  - `CREATE INDEX idx_idempotency_retention ON idempotency_records (status, created_at) WHERE status = 'RESOLVED';`
- **Rationale**:
  - Partial indexes index only relevant rows (a tiny fraction of total table volume), keeping index write overhead near zero during normal payment processing while guaranteeing $O(\log N)$ scan times for the recovery reaper and retention cleaner.

---

## Decision 5: Real-Time Cluster Telemetry on Operator Dashboard

- **Decision**: Expose cluster topology endpoints (`/api/v1/cluster/nodes`, `/api/v1/cluster/leader`, `/api/v1/cluster/retention/status`) and broadcast live SSE events (`LEADER_ELECTED`, `LEADER_STEPPED_DOWN`, `RETENTION_PRUNED`) to the dashboard.
- **Rationale**:
  - Provides operators with immediate visual confirmation of cluster health, active leader identification, node count, and data retention metrics in the web console.
  - Reuses the existing `useDashboardStream` SSE connection with reactive UI badge updates.
