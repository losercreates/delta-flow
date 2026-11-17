# Tasks: Production Packaging & Real-Time Observability Stack

**Input**: Design documents from `/specs/007-production-packaging/` (`spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`)  
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`  
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description with file path`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (`[US1]`, `[US2]`, `[US3]`, `[US4]`)
- Every task includes an exact file path and clear actionable criteria

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependency management and base project configuration

- [X] T001 Add `io.opentelemetry:opentelemetry-exporter-otlp:1.39.0` and `io.micrometer:micrometer-registry-prometheus` dependencies in `pom.xml`
- [X] T002 [P] Create `.env.example` defining configurable ports (`POSTGRES_PORT=5432`, `REDIS_PORT=6379`, `BACKEND_PORT=8080`, `FRONTEND_PORT=3000`, `PROMETHEUS_PORT=9090`, `GRAFANA_PORT=3001`, `JAEGER_UI_PORT=16686`) and database credentials in `.env.example`
- [X] T003 [P] Create configuration directories for Prometheus and Grafana provisioning in `docker/prometheus/` and `docker/grafana/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core application runtime configuration and containerization definitions required by all user stories

**⚠️ CRITICAL**: Must complete before user story containerization can boot

- [X] T004 Configure Spring Boot Actuator to expose `prometheus` alongside `health,info,metrics` and configure `management.prometheus.metrics.export.enabled=true` in `src/main/resources/application.yml`
- [X] T005 [P] Configure test profile overrides for Prometheus metrics and OTLP exporter in `src/test/resources/application-test.yml`
- [X] T006 [P] Create backend multi-stage `Dockerfile` with compile stage (`eclipse-temurin:21-jdk-jammy`) and minimal JRE runtime stage (`eclipse-temurin:21-jre-jammy`) with `--enable-preview` entrypoint in `Dockerfile`
- [X] T007 [P] Create frontend Nginx configuration with reverse proxy routing `/api/` traffic to `http://deltaflow-backend:8080/api/` and SPA HTML5 history routing in `frontend/nginx.conf`
- [X] T008 [P] Create frontend multi-stage `Dockerfile` with Node 20 build stage and Nginx 1.27 Alpine runtime stage in `frontend/Dockerfile`

**Checkpoint**: Base container builds and application configurations ready

---

## Phase 3: User Story 1 - One-Command Multi-Container Production Stack (Priority: P1) 🎯 MVP

**Goal**: Deliver a unified `docker-compose.yml` bringing up all 7 services (PostgreSQL 16, Redis 7, Backend, Frontend, Jaeger, Prometheus, Grafana) with health checks, persistent volumes, and 30s graceful shutdown.

**Independent Test**: Execute `docker compose up -d`; verify all 7 containers report healthy status via `docker compose ps`; execute a financial transfer via `POST /api/v1/transfers` returning `201 Created` with terminal state in PostgreSQL.

- [X] T009 [P] [US1] Create root `docker-compose.yml` defining all 7 services (`deltaflow-db`, `deltaflow-redis`, `deltaflow-backend`, `deltaflow-frontend`, `jaeger`, `prometheus`, `grafana`), bridge network `deltaflow-network`, named volumes (`deltaflow-db-data`, `deltaflow-redis-data`, `prometheus-data`, `grafana-data`), and declarative health checks in `docker-compose.yml`
- [X] T010 [US1] Configure service dependency conditions (`depends_on: { service: healthy }`), environment bindings from `.env`, and 30s stop grace periods in `docker-compose.yml`
- [X] T011 [P] [US1] Create `Makefile` with convenience targets (`up`, `down`, `logs`, `ps`, `restart`, `clean`, `smoke`) in `Makefile`

**Checkpoint**: User Story 1 complete — full 7-service stack boots and serves traffic with `docker compose up -d`.

---

## Phase 4: User Story 2 - End-to-End Distributed Trace Visualization with Jaeger & OTLP (Priority: P1)

**Goal**: Stream W3C distributed traces (`traceparent`) generated across virtual threads and saga steps via OTLP to Jaeger, visualizing complete waterfall timelines.

**Independent Test**: Submit a financial transfer request and verify that querying the Jaeger API or UI at `http://localhost:16686` displays correlated spans with `sagaId`, `stepName`, and virtual thread attributes.

- [X] T012 [P] [US2] Integration test verifying OTLP trace exporter registers spans and gracefully degrades to no-op when collector is offline in `src/test/java/io/deltaflow/observability/integration/OtlpTraceExporterIntegrationTest.java`
- [X] T013 [US2] Implement `OpenTelemetryConfig.java` Spring bean wiring `OtlpHttpSpanExporter` with `BatchSpanProcessor` and fallback to no-op exporter in `src/main/java/io/deltaflow/config/OpenTelemetryConfig.java`
- [X] T014 [US2] Update `DeltaFlowTracer.java` to ensure span attributes (`saga.id`, `saga.step.index`, `saga.step.name`, `thread.name`, `w3c.traceId`) are formatted for OTLP export and compensation spans link to root trace in `src/main/java/io/deltaflow/observability/tracing/DeltaFlowTracer.java`
- [X] T015 [US2] Configure Jaeger service definition in `docker-compose.yml` with OTLP receivers enabled (`COLLECTOR_OTLP_ENABLED=true`) on ports 4317 (gRPC), 4318 (HTTP), and 16686 (UI) in `docker-compose.yml`

**Checkpoint**: User Story 2 complete — distributed traces stream to Jaeger and display full waterfall timelines.

---

## Phase 5: User Story 3 - Real-Time Operational Metrics with Prometheus & Pre-Provisioned Grafana (Priority: P2)

**Goal**: Expose custom Micrometer domain metrics via Actuator, scrape them continuously via Prometheus, and visualize them on an out-of-the-box Grafana dashboard.

**Independent Test**: Trigger API transactions and verify `/actuator/prometheus` exposes custom metrics; open Grafana at `http://localhost:3001` and verify live panels render throughput, p99 saga latency, and cache hit ratios.

- [X] T016 [P] [US3] Integration test verifying Prometheus scrape endpoint `/actuator/prometheus` exposes custom DeltaFlow metrics in `src/test/java/io/deltaflow/observability/integration/PrometheusMetricsIntegrationTest.java`
- [X] T017 [US3] Implement `DeltaFlowMetrics.java` registering meters: `deltaflow.idempotency.requests.total`, `deltaflow.saga.duration`, `deltaflow.saga.steps.active`, `deltaflow.saga.steps.total`, `deltaflow.lock.acquisition.time`, and `deltaflow.compensations.total` in `src/main/java/io/deltaflow/observability/metrics/DeltaFlowMetrics.java`
- [X] T018 [US3] Integrate `DeltaFlowMetrics` into `IdempotencyCoordinator.java` to record requests by status tag (`direct`, `cached`, `conflict`, `locked`) in `src/main/java/io/deltaflow/idempotency/service/IdempotencyCoordinator.java`
- [X] T019 [US3] Integrate `DeltaFlowMetrics` into `SagaCoordinator.java` to record saga durations, active steps, step totals, and compensations in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`
- [X] T020 [P] [US3] Create Prometheus scrape configuration file collecting metrics from `deltaflow-backend:8080/actuator/prometheus` every 5 seconds in `docker/prometheus/prometheus.yml`
- [X] T021 [P] [US3] Create Grafana datasource provisioning file registering Prometheus and Jaeger in `docker/grafana/provisioning/datasources/datasources.yml`
- [X] T022 [P] [US3] Create Grafana dashboard provider configuration in `docker/grafana/provisioning/dashboards/dashboards.yml`
- [X] T023 [P] [US3] Create pre-built Grafana dashboard JSON ("DeltaFlow Operational Reliability") with panels for throughput, idempotency ratio, saga latency percentiles, and lock contention in `docker/grafana/dashboards/deltaflow-overview.json`

**Checkpoint**: User Story 3 complete — Prometheus scrapes metrics and Grafana renders live operational dashboards.

---

## Phase 6: User Story 4 - Turnkey Automation, Health Smoke Verification & Diagnostic Tooling (Priority: P3)

**Goal**: Provide an automated POSIX smoke verification script and Makefile target executing container checks, 4 API validation scenarios, and metrics/trace verification in $<15$ seconds.

**Independent Test**: Execute `./scripts/smoke-test.sh` against the running stack and verify all checks pass with exit code 0.

- [X] T024 [US4] Implement automated POSIX smoke verification script checking container health, executing Scenario 1 (Direct Payment), Scenario 2 (Cached Replay), Scenario 3 (Payload Tampering), Scenario 4 (Compensation), and asserting Prometheus metrics and Jaeger traces in `scripts/smoke-test.sh`
- [X] T025 [P] [US4] Add `smoke` target to `Makefile` invoking `scripts/smoke-test.sh` in `Makefile`
- [X] T026 [US4] Ensure executable permissions and validate script execution in `scripts/smoke-test.sh`

**Checkpoint**: User Story 4 complete — automated smoke test validates full stack health in $<15$ seconds.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: System documentation, end-to-end regression testing, and constitution compliance validation

- [X] T027 [P] Update `README.md` with complete Production Packaging documentation, Docker Compose quickstart, architecture diagram, and ports table in `README.md`
- [X] T028 Execute quickstart validation scenarios 1–5 from `specs/007-production-packaging/quickstart.md`
- [X] T029 Run full regression test suite `mvn test -B` with Testcontainers verifying all unit, integration, and chaos tests pass with 0 failures
- [X] T030 Validate constitution compliance across all new files and update OpenAPI contracts

---

## Dependencies & Execution Order

### Phase Dependencies
- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Phase 1 completion — BLOCKS all user stories.
- **User Story 1 (Phase 3 - MVP)**: Depends on Foundational completion.
- **User Story 2 (Phase 4)**: Depends on Foundational completion.
- **User Story 3 (Phase 5)**: Depends on Foundational completion.
- **User Story 4 (Phase 6)**: Depends on US1, US2, and US3 completion (verifies the full stack).
- **Polish (Phase 7)**: Depends on all user stories being complete.

### Parallel Opportunities
- Within Phase 1: `T002` and `T003` can run in parallel.
- Within Phase 2: `T005`, `T006`, `T007`, and `T008` can run in parallel.
- User Story 1 (`T009`, `T011`), User Story 2 (`T012`), and User Story 3 (`T016`, `T020`, `T021`, `T022`, `T023`) have parallel implementation tasks across independent files.
- Phase 7: `T027` can run in parallel with verification runs.

---

## Implementation Strategy

### MVP First (User Story 1 Only)
1. Complete Phase 1: Setup (`pom.xml`, `.env.example`, directories).
2. Complete Phase 2: Foundational (`application.yml`, Dockerfiles, Nginx config).
3. Complete Phase 3: User Story 1 (`docker-compose.yml`, `Makefile`).
4. **Validate MVP**: Run `docker compose up -d` and verify backend and frontend boot and respond to health probes.

### Incremental Delivery
1. Add User Story 2: Implement OTLP exporter in Spring Boot and verify traces in Jaeger UI.
2. Add User Story 3: Implement custom Micrometer metrics, Prometheus scraper, and Grafana dashboard.
3. Add User Story 4: Implement `./scripts/smoke-test.sh` to automate end-to-end regression validation.
4. Final Polish: Update README and verify 100% test suite pass rate.
