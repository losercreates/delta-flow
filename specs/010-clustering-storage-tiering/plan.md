# Implementation Plan: High Availability, Distributed Leader Coordination & Storage Tiering

**Branch**: `010-clustering-storage-tiering` | **Date**: 2026-11-21 | **Spec**: [spec.md](file:///home/mayukh/projects/delta-flow/specs/010-clustering-storage-tiering/spec.md)

**Input**: Feature specification from `specs/010-clustering-storage-tiering/spec.md`

---

## Summary

Extend DeltaFlow for multi-node horizontal clustering, automated worker crash healing, and sustainable storage tiering:
1. **In-Flight Outbox Lease Recovery Reaper**: Add explicit lease timestamps (`lease_expires_at`) and worker attribution to `outbox_events`, with a background reaper that detects orphaned `IN_FLIGHT` events after worker crashes and safely resets them to `PENDING` with retry increment and audit logging.
2. **Distributed Leader Coordination**: Implement a Redis-backed leader election service (`ClusterLeaderElectionService`) with atomic lease expiration (5s) and automatic heartbeat renewal, ensuring that only one active node per cluster triggers background scheduled jobs (`SagaRecoveryScanner`, `OutboxPublisher`, retention cleaners) with sub-5-second failover.
3. **Automated Idempotency Retention Pruning**: Build a scheduled data retention manager (`IdempotencyRetentionService`) executing bounded batch purges on terminal `RESOLVED` idempotency records older than a configurable SLA window (default 30 days) while strictly protecting active `PROCESSING` lock leases.
4. **PostgreSQL Index Optimization**: Add partial indexes on `outbox_events(status, lease_expires_at)` and `idempotency_records(status, created_at)` via Flyway migration `V6__outbox_lease_and_clustering.sql`.
5. **Cluster Topology Telemetry & Dashboard UI**: Expose cluster node metadata, leader status, and retention statistics over REST and live SSE streaming to the Operator Dashboard.

---

## Technical Context

**Language/Version**: Java 21 LTS (Virtual Threads), TypeScript 5.2+ (React 18)  
**Primary Dependencies**: Spring Boot 3.3.5, Spring Data JPA, Redisson 3.37.0 (`RedissonClient`), Flyway, React 18, Vite, Lucide React, Tailwind CSS  
**Storage**: PostgreSQL 16 (`outbox_events`, `idempotency_records`, `saga_instances`), Redis 7 (`deltaflow:cluster:leader`, `deltaflow:cluster:nodes`)  
**Testing**: JUnit 5, AssertJ, Testcontainers (PostgreSQL, Redis, Kafka, Toxiproxy)  
**Target Platform**: Linux server (Multi-instance clustered JVMs) & Web browser console  
**Project Type**: Distributed High-Availability Web Service & Operator Console  
**Performance Goals**: In-flight recovery scan $<50\text{ms}$; Leader failover $<5\text{s}$; Retention batch pruning $<200\text{ms}$ per 500 records without degrading concurrent payment throughput ($<5\%$ impact)  
**Constraints**: Zero double-spend & zero orphaned records; active lock leases strictly protected from deletion; single-active coordinator guarantee; 100% test coverage under Testcontainers  
**Scale/Scope**: Horizontally scalable multi-replica clusters, 10,000+ tx/sec capacity, multi-year retention compliance  

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evaluation & Architectural Guarantee |
| :--- | :---: | :--- |
| **I. Zero Double-Spend & Financial Invariance** | **PASS** | Retention cleaner strictly purges `RESOLVED` records past 30 days; active `PROCESSING` and `PENDING` records are never touched. In-flight outbox reaper guarantees at-least-once domain event publication. |
| **II. Strict Backward Compensation & Zombie-Proofing** | **PASS** | Leader election coordinates `SagaRecoveryScanner` without altering fencing tokens or LIFO rollback order. |
| **III. Compile-Time Exhaustion & Type Safety** | **PASS** | Cluster roles (`LEADER`, `STANDBY`) and events modeled using Java 21 records and sealed types. |
| **IV. Test-Driven & Chaos-Verified** | **PASS** | Validated via Testcontainers multi-instance tests simulating worker SIGKILL termination, leader lease expiration, and concurrent retention sweeps. |
| **V. Observability & Immutable Audit Trail** | **PASS** | Every in-flight reclamation, leader election transition, and retention pruning sweep writes an immutable audit record with operator attribution. |

---

## Project Structure

### Documentation (this feature)

```text
specs/010-clustering-storage-tiering/
├── spec.md              # Feature specification
├── plan.md              # This implementation plan
├── research.md          # Technical research & architectural decisions
├── data-model.md        # Schema migrations, DTOs & state lifecycles
├── quickstart.md        # Verification and testing scenarios
├── contracts/
│   └── cluster-ha-api.yaml # OpenAPI 3.0 specification
└── checklists/
    └── requirements.md  # Quality validation checklist
```

### Source Code Layout

```text
src/
├── main/
│   ├── java/io/deltaflow/
│   │   ├── cluster/
│   │   │   ├── api/
│   │   │   │   └── ClusterApiController.java        # Cluster topology & retention endpoints
│   │   │   ├── domain/
│   │   │   │   ├── ClusterNodeDto.java              # Cluster node status model
│   │   │   │   ├── LeaderStatusDto.java             # Leader lease DTO
│   │   │   │   ├── RetentionSummaryDto.java         # Retention metrics DTO
│   │   │   │   ├── RetentionTriggerRequest.java     # Manual retention trigger DTO
│   │   │   │   └── OutboxLeaseReclaimResult.java    # Reaper result DTO
│   │   │   └── service/
│   │   │       ├── ClusterLeaderElectionService.java # Redisson-backed leader election & heartbeats
│   │   │       └── IdempotencyRetentionService.java # Bounded batch retention cleaner
│   │   ├── outbox/
│   │   │   ├── repository/
│   │   │   │   └── OutboxEventRepository.java       # Updated with in-flight lease queries
│   │   │   └── service/
│   │   │       ├── OutboxPublisher.java             # Outbox publisher with lease tracking
│   │   │       └── OutboxLeaseRecoveryReaper.java   # Background in-flight recovery reaper
│   │   └── saga/
│   │       └── service/
│   │           └── SagaRecoveryScanner.java         # Wired to execute only on cluster leader
│   └── resources/
│       └── db/migration/
│           └── V6__outbox_lease_and_clustering.sql  # Schema migration & partial indexes
└── test/
    └── java/io/deltaflow/
        └── cluster/
            ├── ClusterLeaderElectionIntegrationTest.java # Failover & leader election test
            ├── InFlightOutboxLeaseRecoveryIntegrationTest.java # Crash recovery reaper test
            └── IdempotencyRetentionIntegrationTest.java  # Batch pruning & lock safety test

frontend/src/
├── components/
│   └── cluster/
│       └── ClusterTopologyDashboard.tsx             # Cluster nodes & retention UI
├── services/
│   └── clusterApi.ts                                # API client for cluster endpoints
└── types/
    └── cluster.ts                                   # Cluster TypeScript definitions
```

---

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| :--- | :--- | :--- |
| None | All designs strictly follow Constitution and Milestone constraints | N/A |
