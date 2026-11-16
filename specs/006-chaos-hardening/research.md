# Research & Technical Decisions: Chaos Testing, OpenTelemetry & Production Hardening

## Decision 1: Network Chaos Injection Topology (Toxiproxy with Testcontainers)

### Decision
Use **Shopify Toxiproxy** orchestrated via Testcontainers (`org.testcontainers:toxiproxy` and `eu.rekawek.toxiproxy:toxiproxy-java`) to introduce controlled network chaos between the DeltaFlow application and its backing infrastructure (PostgreSQL 16 and Redis 7).

### Rationale
- **Containerized Determinism**: Toxiproxy runs as a lightweight TCP proxy container alongside PostgreSQL and Redis in test environments without requiring host-level network alterations (`iptables` / `tc`).
- **Dynamic Toxic Injection**: Allows programmatic manipulation of network conditions on the fly:
  - **Packet Loss / Drop**: Drops a percentage of network packets (e.g. 30% packet loss) to simulate hostile cloud network links.
  - **Latency & Jitter**: Injects artificial round-trip latency (e.g. 500ms ± 200ms) to trigger distributed lease timeouts.
  - **Connection Cuts & Partitions**: Severing TCP connections mid-saga to test connection pool self-healing and crash recovery scanner resumption.
- **Constitution Compliance**: Satisfies Constitution Principle IV (Test-Driven & Chaos-Verified).

### Alternatives Considered
- **Chaos Mesh / LitmusChaos**: Kubernetes-native chaos engines requiring full Kubernetes clusters; impractical for fast, deterministic integration test suites in CI.
- **Application-Level Fault Injection (Mocks / Spies)**: Fails to reproduce kernel TCP buffer backpressure, connection pool starvation, and socket read timeout behavior.

---

## Decision 2: Automated Financial Invariant Verification Engine

### Decision
Implement a dedicated **Financial Invariant Verification Engine** (`FinancialInvariantVerifier`) that validates double-entry ledger balance conservation across all simulated accounts:
$$\sum \text{Debits} = \sum \text{Credits} + \sum \text{Refunds}$$
with exactly $0.00$ discrepancy after concurrent chaos workloads.

### Rationale
- **Mathematical Invariance**: Financial applications cannot settle for "no unhandled exceptions"; they must prove that no monetary units were created, lost, or duplicated during network failures or process restarts (Constitution Principle I).
- **Comprehensive Audit Vectors**:
  - **Account Balances**: Net balance across all participants equals initial capital.
  - **Idempotency Guard**: Replayed client requests with identical keys execute exactly once; no double-spend.
  - **Backward Compensation Accounting**: Every aborted forward debit has an audited, completed refund record.
- **Automated Certification**: Outputs an immutable `InvariantVerificationReport` with exact debit/credit/refund tallies and cryptographic assertion proofs.

### Alternatives Considered
- **Simple End-of-Test Assertions**: Ad-hoc assertions in individual tests lack holistic verification across thousands of concurrent transactions and do not produce auditable compliance certificates.

---

## Decision 3: OpenTelemetry Distributed Tracing & W3C Context Propagation

### Decision
Adopt the **OpenTelemetry (OTel) Java SDK** (`io.opentelemetry:opentelemetry-api` and `io.opentelemetry:opentelemetry-sdk`) with standard **W3C Trace Context** (`traceparent` and `tracestate`) propagation across HTTP controllers, Virtual Threads, and Transactional Outbox domain events.

### Rationale
- **Industry Standard**: W3C Trace Context is the vendor-neutral standard for distributed tracing across cloud-native microservices.
- **Virtual Thread Compatibility**: Propagates `Context.current()` into Java 21 `StructuredTaskScope` branches, ensuring parallel subtasks retain the parent saga trace identifier.
- **Asynchronous Outbox Correlation**: The outbox publisher serializes the active W3C `traceparent` into the outbox event payload, enabling downstream message consumers to trace back to the initiating HTTP request.
- **Enforces Principle V**: Provides end-to-end trace waterfall visualization and log correlation for forensic auditing.

### Alternatives Considered
- **Spring Cloud Sleuth / Micrometer Tracing Alone**: Lacks seamless integration with custom W3C outbox event schemas and pure virtual thread scopes without full Micrometer configuration overhead.
- **Custom Correlation IDs**: Proprietary headers (e.g., `X-Correlation-ID`) do not integrate with modern observability platforms (Datadog, Honeycomb, Grafana Tempo, Jaeger).

---

## Decision 4: SOC2 & PCI-DSS Immutable Audit Trail Exporter

### Decision
Implement a high-throughput, reactive audit log export engine (`AuditLogExporter`) accessible via `GET /api/v1/audit/export` supporting chunked streaming in **JSON** (Newline-Delimited JSON) and **CSV** formats, protected by SHA-256 cryptographic hash chaining and automated PCI-DSS field redaction.

### Rationale
- **Cryptographic Tamper-Evidence**: Each audit entry incorporates a chained hash:
  $$H_i = \text{SHA-256}(H_{i-1} \parallel \text{eventId} \parallel \text{timestamp} \parallel \text{eventType} \parallel \text{payloadDigest})$$
  Any retroactive alteration, deletion, or insertion of audit entries invalidates the cryptographic chain.
- **PCI-DSS Compliance (Req 3.3 & 10.2)**: Automated masking masks primary account numbers (PAN) to last 4 digits (e.g. `****-****-****-1234`) and strips authorization credentials.
- **Streaming Efficiency**: Reactive streaming via Spring MVC `StreamingResponseBody` prevents memory exhaustion when exporting millions of audit records.

### Alternatives Considered
- **Raw Database Dump / pg_dump**: Exposes internal database schema, bypasses field-level redaction, and requires administrative database credentials.
- **Batch CSV File Generation on Disk**: Writes large temporary files to server disks, creating storage exhaustion hazards and security exposure of unencrypted files.

---

## Decision 5: Production Hardening, Actuator Probes & Graceful Shutdown

### Decision
Configure Spring Boot Actuator with custom health indicators (`DatabaseHealthIndicator`, `RedisLockHealthIndicator`, `CircuitBreakerHealthIndicator`) and activate Spring Boot's graceful shutdown engine with a 30-second draining timeout.

### Rationale
- **Kubernetes Readiness & Liveness**: Separates liveness (is process responsive?) from readiness (can process execute financial transactions?). If database latency exceeds thresholds, the readiness probe fails while liveness remains green, safely shedding traffic without pod restart loops.
- **Zero-Downtime Draining**: On `SIGTERM`, the application pauses HTTP ingress, allows active saga steps to finish WAL writes, safely releases Redis locks, and flushes pending outbox events before process termination.
- **Resource Saturation Guard**: HikariCP and Virtual Thread pool metrics are surfaced via `/actuator/metrics` for proactive autoscaling and alerting.

### Alternatives Considered
- **Default Hard Shutdown (`SIGKILL`)**: Abrupt termination risks stranding in-flight database transactions and leaves distributed lock leases orphaned until timeout expiration.
