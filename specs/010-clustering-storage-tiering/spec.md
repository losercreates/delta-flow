# Feature Specification: High Availability, Distributed Leader Coordination & Storage Tiering

**Feature Branch**: `010-clustering-storage-tiering`  
**Created**: 2026-11-21  
**Status**: Draft  
**Input**: User description: "High Availability, Distributed Leader Coordination & Storage Tiering: Extend DeltaFlow for multi-node horizontal clustering and high-availability resilience. Implement an in-flight outbox lease recovery reaper that automatically detects outbox events stuck in IN_FLIGHT status past a configurable lease timeout (e.g., 60 seconds) due to worker process crashes and resets them to PENDING with retry counters and audit logs. Implement Redis-backed distributed scheduler locking (ShedLock / Redisson Leader Latch) to coordinate background scheduled tasks (SagaRecoveryScanner, OutboxPublisher, and retention cleaners) across clustered instances, ensuring single-active coordinator execution without redundant database scans. Introduce an automated data lifecycle retention manager that prunes or archives RESOLVED idempotency records older than a configurable SLA window (e.g., 30 days) while strictly protecting active PROCESSING leases. Optimize PostgreSQL high-volume tables (outbox_events, saga_instances) with time-partitioned indexing. Expose cluster node heartbeats, leader election state, and instance topology over REST and live SSE streaming to the Operator Dashboard, verified with Testcontainers multi-instance failover integration tests."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - In-Flight Outbox Lease Recovery & Worker Crash Healing (Priority: P1) 🎯 MVP

As a reliability engineer, when worker nodes crash, experience out-of-memory errors, or lose network connectivity mid-dispatch while publishing transactional outbox events, I want the system to automatically detect orphaned events whose dispatch lease has expired and return them to the dispatch queue, so that domain events are never permanently stranded in an in-flight state following an abrupt process termination.

**Why this priority**: Fulfills Constitution Principle I & V. In financial distributed systems, domain events notify accounting ledgers and external systems of settled funds. If an event remains stranded in an in-flight state after a worker crash, downstream systems will never receive the settlement notice, causing severe balance discrepancies.

**Independent Test**: Can be tested by marking an outbox event as in-flight with an expired lease timestamp; running the lease recovery scanner; verifying that the orphaned event is detected, its status is transitioned back to pending, its retry counter is incremented, an audit event is emitted, and subsequent dispatch cycles publish the event cleanly.

**Acceptance Scenarios**:

1. **Given** an outbox event in an in-flight state whose dispatch lease has expired beyond the configured timeout threshold, **When** the outbox recovery scanner executes, **Then** the event is reclaimed, its status transitions back to pending, its retry count increments by 1, and an audit trail log is created.
2. **Given** an outbox event currently being processed by an active worker whose lease has not expired, **When** the recovery scanner executes, **Then** the active event is left untouched.
3. **Given** an in-flight event that has breached the maximum allowable lease recovery attempts, **When** the recovery scanner evaluates it, **Then** the event transitions to the dead-letter queue with diagnostic error details, preventing infinite recovery loops.

---

### User Story 2 - Distributed Leader Coordination & Single-Active Scheduler Clustering (Priority: P1) 🎯 MVP

As a platform infrastructure engineer operating DeltaFlow across multiple horizontally scaled cluster instances, I want scheduled background workers (such as saga crash recovery, outbox polling, and data retention cleaners) to coordinate via distributed leader election, so that exactly one active cluster node triggers each scheduled cycle, preventing redundant database polling, lock thrashing, and duplicate processing across replicas.

**Why this priority**: Scalability and high-availability prerequisite. In multi-instance enterprise deployments, uncoordinated background schedulers cause severe database connection pool exhaustion and redundant lock contention. Distributed leader election guarantees single-active coordinator execution with automated failover.

**Independent Test**: Can be tested by launching two or more application instances sharing the same distributed cache; verifying that exactly one node acquires the coordinator leadership lease; verifying that background scheduled tasks run exclusively on the leader node; and terminating the active leader to confirm that a standby node acquires leadership within a bounded failover window (under 5 seconds) and resumes scheduled work.

**Acceptance Scenarios**:

1. **Given** multiple application instances running concurrently in a cluster, **When** leader election initializes, **Then** exactly one node is elected as the active coordinator, while remaining nodes transition to standby mode.
2. **Given** the active coordinator node running background scheduled tasks, **When** background jobs trigger, **Then** only the leader executes the scanning routines, while standby follower nodes skip execution.
3. **Given** the active coordinator node abruptly crashing, stopping, or disconnecting, **When** its leadership lease expires, **Then** a standby node automatically acquires leadership within 5 seconds, logs a leadership transition event, and assumes coordination responsibilities without human intervention.
4. **Given** a network partition where the former leader is isolated, **When** the leader fails to renew its heartbeat lease, **Then** it immediately steps down from leadership, preventing split-brain execution.

---

### User Story 3 - Automated Idempotency Retention Pruning & Data Lifecycle Tiering (Priority: P2)

As a database administrator and compliance officer, I want completed and resolved transaction idempotency records older than a configurable retention period (such as 30 days) to be automatically pruned or archived in bounded batches, while strictly protecting active and in-flight transactions, so that database table bloat is prevented and query indexing performance remains constant over years of operation.

**Why this priority**: Operational sustainability and constant-time latency. High-throughput financial gateways process millions of idempotency checks per week. Unpruned tables degrade B-Tree index scan performance and inflate storage costs. Retention lifecycle management enforces compliant data retention without risking in-flight lock safety.

**Independent Test**: Can be tested by seeding resolved idempotency records older than the retention threshold alongside active/in-flight records; running the retention manager; verifying that only expired resolved records are pruned, active processing locks are preserved with 100% integrity, and an immutable audit log records the batch pruning summary.

**Acceptance Scenarios**:

1. **Given** resolved transaction records created earlier than the retention threshold, **When** the scheduled retention cleaner runs, **Then** expired records are removed or archived in bounded transaction batches to avoid table locking.
2. **Given** active in-flight or pending transactions, **When** the retention cleaner executes, **Then** non-terminal records are never pruned, regardless of timestamp.
3. **Given** a high-volume database, **When** retention pruning runs, **Then** it operates without causing locks or latency spikes on concurrent payment processing queries.
4. **Given** multi-tenant configurations, **When** retention rules differ per tenant, **Then** the cleaner enforces tenant-specific retention windows where specified.

---

### User Story 4 - Cluster Topology Telemetry & High-Availability Dashboard Visibility (Priority: P2)

As an operations engineer monitoring the cluster from the web console, I want to inspect active cluster nodes, view the currently elected leader, observe heartbeat freshness, and see storage tiering statistics in real time, so that I have immediate visibility into cluster health and failover readiness.

**Why this priority**: Operational transparency (Constitution Principle V). Operators must be able to verify cluster quorum, identify which node is acting as leader, detect lagging nodes, and monitor data retention metrics without inspecting command-line infrastructure directly.

**Independent Test**: Can be tested by opening the Cluster & Nodes view in the Operator Dashboard; verifying that all active cluster nodes appear with their node IDs, IP addresses, uptime, and leader status; and observing real-time status updates when a node heartbeats or when leadership changes.

**Acceptance Scenarios**:

1. **Given** an operator viewing the dashboard, **When** navigating to the Cluster Topology section, **Then** the view displays all connected cluster instances with node ID, role (Leader/Standby), startup time, and last heartbeat age.
2. **Given** a leader failover or node shutdown, **When** the cluster state changes, **Then** a live streaming notification updates the dashboard view within 1 second without page refresh.
3. **Given** data retention pruning cycles executing in the background, **When** records are cleaned, **Then** the dashboard displays the total volume of pruned records and historical storage metrics.

---

### Edge Cases

- **Worker Crash Exactly at In-Flight Claim Boundary**: An instance claims an outbox event, updates the status in the database, and crashes before sending the payload over the network. Handled by the in-flight lease recovery reaper, which detects the expired lease timestamp and safely resets the event to pending.
- **Split-Brain Network Partition**: A network partition isolates the leader node from the cluster cache while it continues running. Handled by short-lived lease renewal; if the leader cannot renew its lease within the grace window, it unconditionally steps down before a new leader is elected.
- **Clock Drift Across Cluster Nodes**: Nodes in the cluster have slightly skewed system clocks. Guarded by using monotonic time or cluster cache server timestamps for lease expiration checks rather than unsynchronized client clocks.
- **Retention Pruning During High-Volume Traffic Burst**: Retention cleaner triggers while thousands of concurrent payments arrive. Guarded by batch limits (e.g. 500 rows per transaction) and sleep pauses between batches, preventing lock escalation or replication lag.
- **Mass Leader Election Contention**: Ten nodes start up simultaneously and contest leadership. Guarded by atomic compare-and-set distributed lock acquisition; exactly one acquires the lock, while others register as followers.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST track an explicit lease expiration timestamp (`lease_expires_at`) for every outbox event transitioned to `IN_FLIGHT` status.
- **FR-002**: System MUST provide an automated in-flight outbox lease recovery reaper that periodically scans for events in `IN_FLIGHT` status whose lease has elapsed, resetting them to `PENDING` with an incremented retry counter.
- **FR-003**: In-flight lease recovery reaper MUST transition events that exceed maximum lease recovery attempts directly to `DEAD_LETTER` status with diagnostic logs.
- **FR-004**: System MUST emit an immutable audit log whenever an in-flight outbox event is reclaimed or dead-lettered.
- **FR-005**: System MUST implement distributed leader coordination ensuring that only one active node per cluster executes scheduled background jobs (`SagaRecoveryScanner`, `OutboxPublisher`, retention cleaners).
- **FR-006**: Standby cluster nodes MUST continuously monitor the leadership lease and automatically participate in leader election if the active leader fails to renew its lease within the configured timeout (maximum 5 seconds).
- **FR-007**: Active leader node MUST publish regular heartbeat renewals to maintain its coordinator status; failing to renew within the lease window MUST force the node to step down.
- **FR-008**: System MUST provide a data retention lifecycle service that periodically prunes or archives terminal `RESOLVED` idempotency records older than the configured retention threshold (default 30 days).
- **FR-009**: Data retention service MUST operate in bounded transactional batches (default 500 records per batch) and strictly protect active, pending, or processing idempotency records from deletion.
- **FR-010**: System MUST expose cluster topology endpoints returning active node IDs, roles (Leader/Standby), host metadata, and heartbeat timestamps.
- **FR-011**: System MUST broadcast cluster state transitions (`LEADER_ELECTED`, `LEADER_STEPPED_DOWN`, `NODE_HEARTBEAT`, `RETENTION_PRUNED`) over the live event stream to dashboard clients.
- **FR-012**: Dashboard UI MUST display a Cluster & High-Availability status panel showing active nodes, leader identification, and data retention metrics.

### Key Entities

- **ClusterNode**: Represents an application instance within the cluster, containing node ID, hostname, port, role (`LEADER`, `STANDBY`), startup timestamp, and last heartbeat timestamp.
- **LeaderLease**: Represents the distributed coordination lease held by the elected coordinator, containing leader node ID, lease acquisition time, and lease expiration time.
- **InFlightLeaseRecovery**: Represents a recovery action performed on an orphaned outbox event, containing event ID, previous worker ID, elapsed lease duration, new status, and timestamp.
- **RetentionPruningSummary**: Represents the result of a data retention cleanup cycle, containing records audited, records pruned, duration milliseconds, oldest remaining record timestamp, and execution timestamp.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Orphaned in-flight outbox events resulting from abrupt process termination are automatically detected and reclaimed within 60 seconds of worker crash.
- **SC-002**: Cluster leader failover completes and resumes background scheduled operations within 5 seconds of active leader termination.
- **SC-003**: Exactly one cluster node executes background scanning tasks at any given moment, with zero duplicate executions across concurrent nodes.
- **SC-004**: Data retention pruning executes without degrading concurrent transaction response times by more than 5%.
- **SC-005**: Zero active or processing transaction locks are ever deleted or corrupted by retention pruning (100% safety).
- **SC-006**: Dashboard displays real-time cluster topology and reflects leader transitions within 1 second of election.

---

## Assumptions

- A shared distributed cache or key-value store (such as Redis) is available for distributed leader leases and cluster heartbeats.
- Cluster nodes have network connectivity to the shared database and cache.
- The retention period for completed idempotency records (default 30 days) satisfies regulatory compliance requirements; audit logs remain permanent and are never pruned by this service.
- High-volume transaction tables utilize appropriate B-Tree index coverage on timestamp and status columns to facilitate efficient batch scanning.
