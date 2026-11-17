# Phase 0 Research: Production Packaging & Real-Time Observability Stack

**Feature Branch**: `007-production-packaging`  
**Date**: 2026-11-17  
**Spec Reference**: [specs/007-production-packaging/spec.md](spec.md)

---

## 1. Multi-Stage Containerization for Backend & Frontend

### Decision 1.1: Backend Container Runtime & Preview Features
* **Decision**: Adopt a multi-stage Docker build utilizing `eclipse-temurin:21-jdk-jammy` for compilation and `eclipse-temurin:21-jre-jammy` for the minimal runtime container. The container entrypoint must explicitly pass `--enable-preview` to JVM options.
* **Rationale**:
  * Project Loom virtual threads and `StructuredTaskScope` require the `--enable-preview` flag in Java 21 LTS.
  * A multi-stage build separates the Maven build cache and source dependencies from the final production image, reducing image size from >800MB to ~250MB.
  * Jammy (Ubuntu LTS) base provides robust glibc compatibility required by rocksdb/redisson native bindings compared to alpine musl quirks.
* **Alternatives Considered**:
  * *Single-stage build with full JDK*: Rejected due to bloated image size and security attack surface in production.
  * *Google Distroless Java 21*: Evaluated, but lacks standard shell tools (`sh`, `wget`, `curl`) often needed for container readiness probe execution in Docker Compose.

### Decision 1.2: Frontend Dashboard Containerization
* **Decision**: Multi-stage build compiling React + Vite in `node:20-alpine`, serving the production static bundle (`frontend/dist`) via `nginx:1.27-alpine`. Nginx is configured to reverse-proxy `/api/` traffic directly to `http://deltaflow-backend:8080/api/` and route all non-file requests to `index.html` (SPA routing).
* **Rationale**:
  * In production, running `vite` or `vite preview` under Node.js consumes excessive memory (~150MB) and is single-threaded.
  * Nginx Alpine uses <15MB of RAM, serves static assets with sub-millisecond latency, and provides a native reverse proxy that eliminates Cross-Origin Resource Sharing (CORS) issues completely.
* **Alternatives Considered**:
  * *Node.js Vite Dev Server in container*: Convenient for local file watching, but unsuited for production packaging and CI smoke testing due to high memory overhead and slow boot time.

---

## 2. Docker Compose v2 Architecture & Dependency Health Probes

### Decision 2.1: Service Startup Ordering & Health Dependencies
* **Decision**: Configure Docker Compose v2 service dependencies using `depends_on` with `condition: service_healthy`:
  ```yaml
  deltaflow-backend:
    depends_on:
      deltaflow-db:
        condition: service_healthy
      deltaflow-redis:
        condition: service_healthy
  ```
  * **Database health check**: `test: ["CMD-SHELL", "pg_isready -U postgres -d deltaflow"]` (interval: 5s, timeout: 3s, retries: 5).
  * **Redis health check**: `test: ["CMD", "redis-cli", "ping"]` (interval: 5s, timeout: 3s, retries: 5).
  * **Backend health check**: `test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/actuator/health/readiness"]` (interval: 5s, timeout: 3s, retries: 10, start_period: 15s).
* **Rationale**:
  * Spring Boot startup fails or crashes if Flyway migrations attempt to run before PostgreSQL has initialized its socket or if Redis connection fails on boot.
  * Strict health check conditions ensure that the backend only boots when persistence dependencies are genuinely ready.
* **Alternatives Considered**:
  * *Wait-for-it shell wrappers*: Invasive, requires custom entrypoint wrapper scripts instead of standard declarative Docker Compose syntax.

### Decision 2.2: Volume Persistence Strategy
* **Decision**: Declare named volumes for stateful services:
  * `deltaflow-db-data` -> `/var/lib/postgresql/data`
  * `deltaflow-redis-data` -> `/data`
  * `prometheus-data` -> `/prometheus`
  * `grafana-data` -> `/var/lib/grafana`
* **Rationale**:
  * Ensures that stopping the container stack (`docker compose stop` or `docker compose down` without `-v`) preserves historical saga instances, audit journals, idempotency keys, and Grafana settings.

---

## 3. OpenTelemetry OTLP Trace Exporter & Jaeger Integration

### Decision 3.1: Programmatic OTLP Export via OpenTelemetry Java SDK
* **Decision**: Add `io.opentelemetry:opentelemetry-exporter-otlp` to `pom.xml`. Configure a Spring `@Configuration` bean (`OpenTelemetryConfig`) that wires an `OtlpHttpSpanExporter` (or `OtlpGrpcSpanExporter`) with a `BatchSpanProcessor` into `GlobalOpenTelemetry`:
  * Configurable via environment variable: `OTEL_EXPORTER_OTLP_ENDPOINT` (e.g., `http://jaeger:4318/v1/traces`).
  * If the endpoint is unconfigured or unreachable, the tracer defaults to a no-op exporter, ensuring unit tests and standalone executions continue without errors.
* **Rationale**:
  * Programmatic SDK configuration is self-contained in the JAR, avoids requiring a separate Java agent `-javaagent:opentelemetry-javaagent.jar` (which can cause ClassLoader conflicts with virtual threads in early Loom releases), and enables custom span attributes from `DeltaFlowTracer`.
* **Alternatives Considered**:
  * *OpenTelemetry Java Auto-Instrumentation Agent*: Requires downloading a 30MB agent JAR into the Docker image, complicates `--enable-preview` startup flags, and can introduce unexpected bytecode manipulation on virtual threads.

### Decision 3.2: Jaeger All-In-One Container
* **Decision**: Use `jaegertracing/all-in-one:1.60` exposing:
  * Port `4317`: OTLP gRPC receiver.
  * Port `4318`: OTLP HTTP receiver.
  * Port `16686`: Jaeger Web UI.
* **Rationale**:
  * Lightweight, zero-config in-memory trace storage ideal for local production preview, development, and testing.

---

## 4. Metrics Instrumentation, Prometheus & Pre-Provisioned Grafana

### Decision 4.1: Micrometer Metrics Registry & Custom Meters
* **Decision**: Add `io.micrometer:micrometer-registry-prometheus` to `pom.xml`. Create a dedicated Spring bean `DeltaFlowMetrics` publishing core domain meters:
  1. `deltaflow.idempotency.requests`: Counter tagged with `status` (`direct`, `cached`, `conflict`, `locked`).
  2. `deltaflow.saga.duration`: Timer with SLA percentiles (p50, p95, p99), tagged with `sagaName` and `status` (`COMPLETED`, `COMPENSATED`, `FAILED`).
  3. `deltaflow.saga.steps.active`: Gauge tracking in-flight forward/compensation steps.
  4. `deltaflow.saga.steps.total`: Counter tagged with `stepName` and `outcome`.
  5. `deltaflow.lock.acquisition.time`: Timer measuring lock acquisition latency, tagged with `lockType` (`redis`, `postgres`).
  6. `deltaflow.compensations.total`: Counter recording backward compensation workflows.
* **Rationale**:
  * Provides precision visibility into idempotency cache hit ratios, lock wait times, and saga latencies directly tied to business financial transactions.

### Decision 4.2: Declarative Grafana Provisioning
* **Decision**: Pre-provision Grafana using declarative YAML files mounted read-only into `/etc/grafana/provisioning/`:
  * `datasources/datasources.yml`: Auto-registers Prometheus (`http://prometheus:9090`) as default datasource and Jaeger (`http://jaeger:16686`) as trace datasource.
  * `dashboards/dashboards.yml`: Configures dashboard provider loading JSON files from `/var/lib/grafana/dashboards/`.
  * Pre-built `deltaflow-overview.json`: Features panels for Request Throughput, Idempotency Status Distribution (Pie/Bar), Saga Latency Percentiles (p50, p95, p99), Step Failure Rates, and Active Lock Contention.
* **Rationale**:
  * Zero-touch user experience: the dashboard is instantly available on `http://localhost:3001` upon `docker compose up` with no manual login or configuration needed.

---

## 5. Automated Health Smoke Verification Script

### Decision 5.1: POSIX Bash Smoke Test Script
* **Decision**: Implement `scripts/smoke-test.sh` and a Makefile target `make smoke`:
  1. Checks all 7 container statuses and health probes via Docker CLI or HTTP endpoints.
  2. Executes Scenario 1: Direct Transfer (`HTTP 201`, `Idempotency-Status: DIRECT`).
  3. Executes Scenario 2: Cached Replay (`HTTP 201`, `Idempotency-Status: CACHED`).
  4. Executes Scenario 3: Payload Tampering Rejection (`HTTP 409`, `IDEMPOTENCY_KEY_PAYLOAD_MISMATCH`).
  5. Executes Scenario 4: Backward Compensation (`HTTP 500` / status `COMPENSATED`).
  6. Queries Prometheus API (`http://localhost:9090/api/v1/query?query=deltaflow_idempotency_requests_total`) to verify metric ingestion.
  7. Queries Jaeger API (`http://localhost:16686/api/traces?service=delta-flow&limit=5`) to verify trace generation.
  8. Emits a clean color-coded test report in terminal.
* **Rationale**:
  * Fast (executes in <15s), requires only standard tools (`curl`, `jq`), and runs equally well on local developer workstations and headless CI/CD runners.
