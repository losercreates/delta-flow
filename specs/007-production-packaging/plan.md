# Implementation Plan: Production Packaging & Real-Time Observability Stack

**Branch**: `007-production-packaging` | **Date**: 2026-11-17 | **Spec**: [specs/007-production-packaging/spec.md](spec.md)

**Input**: Feature specification from `specs/007-production-packaging/spec.md`

---

## Summary

Milestone 7 packages DeltaFlow for turnkey production deployments, local developer onboarding, and real-time enterprise observability through:
1. **Multi-Container Production Stack (`docker-compose.yml`)**: Single-command startup (`docker compose up -d`) orchestrating 7 services: PostgreSQL 16, Redis 7, DeltaFlow Backend (Spring Boot 3.3.5 / Java 21 preview), React/Vite Operator Dashboard (Nginx Alpine reverse proxy), Jaeger (OTLP receiver), Prometheus, and Grafana.
2. **End-to-End OTLP Distributed Tracing**: Programmatic OpenTelemetry SDK integration streaming W3C traces (`traceparent`) over OTLP to Jaeger with zero Java agent overhead, visualizing virtual thread waterfall execution across forward and compensating saga steps.
3. **Custom Micrometer Domain Metrics**: Publishing core business metrics via Spring Boot Actuator (`/actuator/prometheus`) tracking idempotency cache hit ratios, saga duration percentiles (p50, p95, p99), distributed lock acquisition latency, and step rollbacks.
4. **Pre-Provisioned Grafana Dashboard**: Auto-provisioned Prometheus & Jaeger datasources and a turnkey operational dashboard on port 3001 with zero manual setup.
5. **Turnkey Automation & Health Smoke Script**: POSIX verification script (`scripts/smoke-test.sh` / `make smoke`) verifying container health, 4 financial API scenarios, metrics scraping, and trace ingestion in under 15 seconds.

---

## Technical Context

**Language/Version**: Java 21 LTS (Virtual Threads, Structured Concurrency, Sealed Interfaces, Record Patterns)  
**Primary Dependencies**:
- Spring Boot 3.3.5 (`spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-data-jpa`)
- OpenTelemetry Java SDK & Exporter (`io.opentelemetry:opentelemetry-api:1.39.0`, `io.opentelemetry:opentelemetry-sdk:1.39.0`, `io.opentelemetry:opentelemetry-exporter-otlp:1.39.0`)
- Micrometer Prometheus Registry (`io.micrometer:micrometer-registry-prometheus`)
- Redisson 3.37.0 (`redisson-spring-boot-starter`)  
**Containerization & Infrastructure**:
- Docker Compose v2, Docker Engine 20.10+
- Base Images: `eclipse-temurin:21-jdk-jammy` (build), `eclipse-temurin:21-jre-jammy` (backend runtime), `node:20-alpine` (frontend build), `nginx:1.27-alpine` (frontend runtime)
- Infrastructure Containers: `postgres:16-alpine`, `redis:7-alpine`, `jaegertracing/all-in-one:1.60`, `prom/prometheus:v2.54.1`, `grafana/grafana:11.2.0`  
**Storage**: PostgreSQL 16 (named volume `deltaflow-db-data`), Redis 7 (named volume `deltaflow-redis-data`)  
**Testing**: JUnit 5, AssertJ, Testcontainers 1.20.6, automated bash smoke script (`scripts/smoke-test.sh`)  
**Target Platform**: Containerized Linux / Docker Desktop / Cloud VM  
**Project Type**: Multi-service distributed orchestrator platform  
**Performance Goals**:
- Full 7-service stack cold boot to healthy status in $< 60$ seconds
- Sub-10ms idempotency cache response time in production container
- Automated health smoke verification execution $< 15$ seconds  
**Constraints**:
- Strict adherence to Constitution Principles I–V
- Java 21 `--enable-preview` enabled in container runtime entrypoints
- Named volume persistence across container restarts
- Sub-30s graceful shutdown draining  
**Scale/Scope**: Production packaging, real-time observability, and developer onboarding automation

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Requirement | Design Compliance | Gate Status |
|---|---|---|---|
| **I. Zero Double-Spend & Financial Invariance** | SHA-256 payload digests, dual-stage idempotency locks, zero balance drift across retries | Production stack runs against real PostgreSQL 16 and Redis 7; smoke test asserts direct, cached replay, tampering rejection, and compensation. | **PASS** |
| **II. Strict Backward Compensation & Zombie-Proofing** | Monotonic fencing tokens, deterministic rollback on failure, rejection of late steps | Trace exporter tags compensating spans linked to failed forward spans; smoke test scenario 4 verifies backward compensation. | **PASS** |
| **III. Compile-Time Exhaustion & Type Safety** | Sealed interfaces and record patterns; no wildcard default catch-alls | Metrics and tracing configuration use Java records (`OtlpTraceExporterConfig`, `DeltaFlowMetricDefinition`) and sealed status enums. | **PASS** |
| **IV. Test-Driven & Chaos-Verified** | Automated validation; real containerized databases | Smoke test script (`smoke-test.sh`) automates end-to-end integration verification against real Docker containers with 100% pass requirement. | **PASS** |
| **V. Observability & Immutable Audit Trail** | W3C traceparent propagation, structured JSON logs, tamper-evident audit export | OTLP exporter streams W3C traces to Jaeger; Prometheus scrapes custom Micrometer metrics; Grafana renders live operational dashboard. | **PASS** |

*Overall Gate Status*: **ALL PASS** — No constitutional violations.

---

## Project Structure

### Documentation (this feature)

```text
specs/007-production-packaging/
├── spec.md              # Feature specification
├── plan.md              # This implementation plan (/speckit-plan output)
├── research.md          # Architectural decisions & research outcomes (Phase 0 output)
├── data-model.md        # Container, metrics, and trace models (Phase 1 output)
├── quickstart.md        # 5 end-to-end runnable validation scenarios (Phase 1 output)
├── contracts/           # Infrastructure & API interface contracts (Phase 1 output)
│   ├── docker-compose-spec.yaml
│   └── observability-metrics-spec.yaml
├── checklists/          # Quality validation checklists
│   └── requirements.md
└── tasks.md             # Phase 2 implementation task list (generated via /speckit-tasks)
```

### Source Code Layout

```text
delta-flow/
├── docker-compose.yml                          # 7-service multi-container orchestration specification
├── Dockerfile                                  # Multi-stage Java 21 backend build (JDK compile -> JRE runtime)
├── .env.example                                # Configurable environment variables and port mappings
├── Makefile                                    # Convenient commands (up, down, smoke, logs, clean)
├── docker/
│   ├── prometheus/
│   │   └── prometheus.yml                      # Scrape config for deltaflow-backend:8080/actuator/prometheus
│   └── grafana/
│       ├── provisioning/
│       │   ├── datasources/
│       │   │   └── datasources.yml             # Auto-provisioned Prometheus & Jaeger datasources
│       │   └── dashboards/
│       │       └── dashboards.yml              # Dashboard provider definition
│       └── dashboards/
│           └── deltaflow-overview.json         # Pre-built operational reliability Grafana dashboard
├── frontend/
│   ├── Dockerfile                              # Multi-stage frontend build (Node compile -> Nginx Alpine runtime)
│   └── nginx.conf                              # Reverse proxying /api/ to backend:8080 + SPA routing
├── scripts/
│   └── smoke-test.sh                           # Turnkey health & API validation smoke test script
└── src/main/java/io/deltaflow/
    ├── config/
    │   └── OpenTelemetryConfig.java            # Spring configuration wiring OtlpHttpSpanExporter & BatchSpanProcessor
    └── observability/
        └── metrics/
            └── DeltaFlowMetrics.java           # Custom Micrometer meters for idempotency, saga latency, and locks
```

---

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| *None* | *All architectural choices strictly adhere to Constitution Principles I–V.* | *N/A* |
