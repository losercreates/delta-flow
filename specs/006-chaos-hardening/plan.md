# Implementation Plan: Chaos Testing, OpenTelemetry & Production Hardening

**Branch**: `006-chaos-hardening` | **Date**: 2026-11-16 | **Spec**: [specs/006-chaos-hardening/spec.md](spec.md)

**Input**: Feature specification from `specs/006-chaos-hardening/spec.md`

---

## Summary

Milestone 6 hardens DeltaFlow for enterprise production deployment through:
1. **Network Chaos Injection**: Automated Toxiproxy test harness simulating 30% packet loss, latency spikes, and socket cuts against PostgreSQL and Redis.
2. **Automated Invariant Verification**: Mathematical proof of zero balance discrepancy ($\sum \text{Debits} == \sum \text{Credits} + \sum \text{Refunds}$) and zero double-spends across high-concurrency chaos runs.
3. **OpenTelemetry Distributed Tracing**: Standard W3C `traceparent` context propagation across HTTP ingress, virtual threads, saga steps, and transactional outbox dispatches.
4. **SOC2/PCI-DSS Immutable Audit Exporter**: High-performance streaming export (`GET /api/v1/audit/export`) in NDJSON and CSV with SHA-256 cryptographic chaining and PCI-DSS data masking.
5. **Production Hardening**: Kubernetes-ready liveness/readiness probes and 30-second graceful shutdown draining.

---

## Technical Context

**Language/Version**: Java 21 LTS (Project Loom Virtual Threads, Sealed Interfaces, Record Patterns)  
**Primary Dependencies**:
- Spring Boot 3.3.5 (`spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`)
- OpenTelemetry Java SDK (`io.opentelemetry:opentelemetry-api:1.39.0`, `io.opentelemetry:opentelemetry-sdk:1.39.0`)
- Resilience4j 2.2.0 (`resilience4j-spring-boot3`)
- Redisson 3.37.0 (`redisson-spring-boot-starter`)
- Testcontainers 1.20.6 (`testcontainers-bom`, `org.testcontainers:toxiproxy`, `org.testcontainers:postgresql`)
- Toxiproxy Java Client (`eu.rekawek.toxiproxy:toxiproxy-java:2.1.7`)  
**Storage**: PostgreSQL 16 (Relational state & write-ahead journal), Redis 7 (Distributed lock leases)  
**Testing**: JUnit 5, AssertJ, RestAssured, Testcontainers (PostgreSQL, Redis, Toxiproxy)  
**Target Platform**: Linux server / Containerized Kubernetes deployment  
**Project Type**: Distributed Idempotency and Saga Orchestration Service  
**Performance Goals**:
- 100% financial balance invariant integrity (`Discrepancy == 0.00`) across 1,000+ chaos-injected runs
- 0 unhandled thread leaks under 30% packet loss
- Streaming audit export throughput $\ge 5,000$ records/second  
**Constraints**:
- Strict adherence to Constitution Principles I–V (Zero Double-Spend, Strict Backward Compensation, Sealed Pattern-Matching, Chaos-Verified, Immutable Audit Trail)
- W3C `traceparent` propagation across virtual threads and transactional outbox events
- Sub-30s graceful shutdown draining  
**Scale/Scope**: Multi-tenant financial API orchestration under network faults

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Requirement | Design Compliance | Gate Status |
|---|---|---|---|
| **I. Zero Double-Spend & Financial Invariance** | SHA-256 payload digests, dual-stage idempotency locks, zero balance drift across retries | `FinancialInvariantVerifier` mathematically proves $\sum \text{Debits} == \sum \text{Credits} + \sum \text{Refunds}$ across all chaos runs with 0 duplicate executions. | **PASS** |
| **II. Strict Backward Compensation & Zombie-Proofing** | Monotonic fencing tokens, deterministic rollback on failure, rejection of late steps | Toxiproxy tests drop 30% packets mid-saga; steps verify that halted transactions roll back cleanly and stale responses are fenced out. | **PASS** |
| **III. Compile-Time Exhaustion & Type Safety** | Sealed interfaces and record patterns; no wildcard default catch-alls | New domain entities (`ChaosSimulation`, `InvariantVerificationReport`, `AuditExportRecord`) use Java 21 records and sealed hierarchies. | **PASS** |
| **IV. Test-Driven & Chaos-Verified** | Automated Toxiproxy chaos tests with $\ge 30\%$ packet loss; real containerized databases | `ToxiproxyNetworkChaosIntegrationTest` uses containerized Toxiproxy against real PostgreSQL 16 and Redis 7 containers. | **PASS** |
| **V. Observability & Immutable Audit Trail** | W3C traceparent propagation, structured JSON logs, tamper-evident audit export | OpenTelemetry tracer propagates W3C headers across virtual threads and outbox; `AuditLogExporter` outputs chained SHA-256 logs. | **PASS** |

*Overall Gate Status*: **ALL PASS** — No constitutional violations.

---

## Project Structure

### Documentation (this feature)

```text
specs/006-chaos-hardening/
├── spec.md              # Feature specification
├── plan.md              # This implementation plan
├── research.md          # Architectural decisions & research outcomes
├── data-model.md        # Domain entities, reports, and audit models
├── quickstart.md        # 5 end-to-end runnable validation scenarios
├── contracts/           # API interface specifications
│   └── observability-api.yaml
├── checklists/          # Quality validation checklists
│   └── requirements.md
└── tasks.md             # Phase 2 implementation task list (generated via /speckit-tasks)
```

### Source Code Layout

```text
src/main/java/io/deltaflow/
├── audit/
│   ├── api/
│   │   └── AuditExportController.java         # GET /api/v1/audit/export (JSON/CSV streaming)
│   ├── dto/
│   │   ├── AuditExportRecord.java             # Normalized PCI-DSS sanitized record
│   │   └── AuditExportFormat.java             # JSON / CSV enum
│   └── service/
│       ├── AuditLogExporter.java              # Streaming exporter with SHA-256 hash chaining
│       └── PciDssMaskingService.java          # PAN masking and credentials redaction
├── chaos/
│   ├── domain/
│   │   ├── InvariantVerificationReport.java   # Financial balance certification record
│   │   ├── InvariantViolation.java            # Detailed discrepancy record
│   │   └── AccountBalanceSnapshot.java        # Balance comparison snapshot
│   ├── api/
│   │   └── InvariantVerificationController.java # GET /api/v1/invariants/verify
│   └── service/
│       └── FinancialInvariantVerifier.java    # Automated double-entry audit engine
├── observability/
│   ├── filter/
│   │   └── W3CTraceContextFilter.java         # Extracts/injects W3C traceparent headers
│   ├── tracing/
│   │   ├── TraceContextHolder.java            # Virtual thread safe context accessor
│   │   └── DeltaFlowTracer.java               # OpenTelemetry span builder for sagas/steps
│   └── health/
│       ├── DatabaseHealthIndicator.java       # Custom DB responsiveness probe
│       ├── RedisLockHealthIndicator.java      # Redis lock lease latency probe
│       └── GracefulShutdownHandler.java       # WAL flusher & draining coordinator
src/test/java/io/deltaflow/
├── chaos/
│   └── integration/
│       ├── ToxiproxyNetworkChaosIntegrationTest.java # 30% packet loss & partition tests
│       └── FinancialInvariantVerificationTest.java   # Ledger balance integrity tests
├── audit/
│   └── integration/
│       └── AuditExportIntegrationTest.java           # NDJSON/CSV streaming & hash chain tests
└── observability/
    └── integration/
        ├── W3CTracePropagationIntegrationTest.java   # OpenTelemetry trace continuity tests
        └── ProductionHealthProbesIntegrationTest.java # Actuator liveness/readiness tests
```

---

## Complexity Tracking

> No constitutional violations or unwarranted complexity introduced. All components strictly align with project principles.

| Component | Necessity | Why Simpler Alternative Rejected |
|---|---|---|
| Toxiproxy Containerization | Required to simulate real TCP/socket kernel degradation under 30% loss | Mocking network calls fails to test OS socket buffers and driver connection pools |
| SHA-256 Hash Chaining | Required for SOC2/PCI-DSS tamper-evidence and regulatory non-repudiation | Unchained audit logs can be surreptitiously deleted or reordered without detection |
| W3C Traceparent Header | Required for vendor-neutral distributed tracing across microservices | Custom headers (e.g. `X-Correlation-Id`) do not interoperate with cloud OTel collectors |
