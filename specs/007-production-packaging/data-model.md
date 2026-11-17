# Phase 1 Data Model: Production Packaging & Real-Time Observability Stack

**Feature Branch**: `007-production-packaging`  
**Date**: 2026-11-17  
**Spec Reference**: [specs/007-production-packaging/spec.md](spec.md)

---

## 1. Container Infrastructure Models

### 1.1 `ContainerServiceDefinition`
Represents the structural specification of each orchestrated service within the multi-container stack.

```text
+---------------------------------------------------------------------------------+
|                            ContainerServiceDefinition                           |
+---------------------------------------------------------------------------------+
| serviceName     : String                 (e.g., "deltaflow-backend")            |
| containerName   : String                 (e.g., "deltaflow-backend")            |
| image           : String                 (e.g., "deltaflow/backend:latest")     |
| buildContext    : Optional<String>       (e.g., "." or "./frontend")            |
| dockerfilePath  : Optional<String>       (e.g., "Dockerfile")                   |
| portMappings    : List<PortMapping>      (e.g., ["8080:8080"])                  |
| environmentVars : Map<String, String>    (e.g., DB credentials, Redis URLs)     |
| healthCheck     : ContainerHealthCheck   (Command, interval, timeout, retries)  |
| restartPolicy   : String                 (e.g., "unless-stopped")               |
| volumeMounts    : List<VolumeMount>      (e.g., "deltaflow-db-data:/var/lib...")|
| network         : String                 (e.g., "deltaflow-network")            |
| dependencies    : List<ServiceDependency>(Service name + condition)             |
+---------------------------------------------------------------------------------+
```

#### Fields & Types
| Field | Type | Description |
| :--- | :--- | :--- |
| `serviceName` | `String` | Unique service identifier in `docker-compose.yml` |
| `containerName` | `String` | Predictable runtime name for container inspection and CLI access |
| `image` | `String` | Docker image tag or registry reference |
| `buildContext` | `String` | Relative path to directory containing Dockerfile |
| `portMappings` | `List<String>` | Host-to-container port bindings (configurable via `.env`) |
| `environmentVars` | `Map<String, String>` | Dynamic environment variable bindings |
| `healthCheck` | `HealthCheckConfig` | Probe command, interval, timeout, retries, and start period |
| `dependencies` | `List<Dependency>` | Upstream services required with health conditions |

---

### 1.2 `ContainerHealthCheck`
Defines the container readiness policy used to orchestrate startup dependencies.

```text
+---------------------------------------------------------------------------------+
|                               ContainerHealthCheck                              |
+---------------------------------------------------------------------------------+
| testCommand     : List<String>           (e.g., ["CMD-SHELL", "pg_isready..."]) |
| interval        : Duration               (e.g., 5s)                             |
| timeout         : Duration               (e.g., 3s)                             |
| retries         : int                    (e.g., 5)                              |
| startPeriod     : Duration               (e.g., 15s)                            |
+---------------------------------------------------------------------------------+
```

---

## 2. Observability & Tracing Configuration Models

### 2.1 `OtlpTraceExporterConfig`
Represents the runtime configuration for streaming distributed W3C spans from the Java runtime to the OpenTelemetry/Jaeger collector.

```text
+---------------------------------------------------------------------------------+
|                             OtlpTraceExporterConfig                             |
+---------------------------------------------------------------------------------+
| enabled         : boolean                (default: true)                        |
| endpoint        : String                 (e.g., "http://jaeger:4318/v1/traces") |
| protocol        : String                 (e.g., "http/protobuf" or "grpc")      |
| timeout         : Duration               (default: 5000ms)                      |
| maxQueueSize    : int                    (default: 2048 spans)                  |
| maxExportBatch  : int                    (default: 512 spans)                   |
| exportInterval  : Duration               (default: 500ms)                       |
+---------------------------------------------------------------------------------+
```

#### Lifecycle State Transitions:
```text
[UNINITIALIZED] 
       │
       ▼ (Spring Boot Context Initialized)
[CONNECTING] ──(Endpoint reachable)──► [ACTIVE_STREAMING]
       │                                       │
       │ (Endpoint unreachable)                │ (Collector crash / backpressure)
       ▼                                       ▼
[FALLBACK_NOOP] ◄─────────────── [BATCH_BUFFERING / DROP_OLDEST]
```

---

## 3. Operational Domain Metrics Models

### 3.1 `DeltaFlowMetricDefinition`
Represents the metric specifications registered with Micrometer and scraped by Prometheus.

| Metric Name | Type | Unit | Tags / Dimensions | Description |
| :--- | :--- | :--- | :--- | :--- |
| `deltaflow.idempotency.requests.total` | `Counter` | requests | `status: direct \| cached \| conflict \| locked` | Measures total idempotency evaluations and cache efficiency |
| `deltaflow.saga.duration` | `Timer` | seconds | `saga: String`, `status: COMPLETED \| COMPENSATED \| FAILED` | End-to-end saga execution latency with p50, p95, p99 percentiles |
| `deltaflow.saga.steps.active` | `Gauge` | steps | `none` | Real-time count of forward and compensating steps currently executing |
| `deltaflow.saga.steps.total` | `Counter` | steps | `step: String`, `outcome: SUCCESS \| FAILED \| COMPENSATED` | Total step completions and rollbacks |
| `deltaflow.lock.acquisition.time` | `Timer` | seconds | `lock_type: redis \| postgres` | Time taken to acquire distributed locks and detect contention |
| `deltaflow.compensations.total` | `Counter` | events | `saga: String` | Total backward compensation workflows initiated |

---

## 4. Health Smoke Verification Models

### 4.1 `SmokeTestExecutionReport`
Data structure output by the automated smoke verification script (`scripts/smoke-test.sh`).

```text
+---------------------------------------------------------------------------------+
|                            SmokeTestExecutionReport                             |
+---------------------------------------------------------------------------------+
| executionId     : UUID                   (Unique test execution run ID)         |
| timestamp       : Instant                (ISO-8601 execution timestamp)        |
| overallStatus   : Enum [PASS, FAIL]      (Global outcome)                       |
| durationMs      : long                   (Total smoke test elapsed time)        |
| containerChecks : List<ContainerCheck>   (Health check outcomes for 7 services) |
| scenarios       : List<ScenarioResult>   (Outcomes of 4 API validation tests)   |
| metricsCheck    : MetricsVerification    (Prometheus scrape verification status)|
| traceCheck      : TraceVerification      (Jaeger trace ingestion status)        |
+---------------------------------------------------------------------------------+
```

#### Sub-Components:
```text
+-----------------------------------------+    +-----------------------------------------+
|             ContainerCheck              |    |             ScenarioResult              |
+-----------------------------------------+    +-----------------------------------------+
| serviceName : String                    |    | scenarioName : String                   |
| status      : String (healthy/running)  |    | httpStatus   : int (e.g. 201, 409)      |
| probeTimeMs : long                      |    | latencyMs    : long                     |
| passed      : boolean                   |    | passed       : boolean                  |
+-----------------------------------------+    +-----------------------------------------+
```
