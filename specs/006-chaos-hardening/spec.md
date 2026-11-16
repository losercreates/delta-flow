# Feature Specification: Chaos Testing, OpenTelemetry & Production Hardening

**Feature Branch**: `006-chaos-hardening`  
**Created**: 2026-11-16  
**Status**: Draft  
**Input**: User description: "Implement Milestone 6: Chaos Testing, OpenTelemetry & Production Hardening. Build a comprehensive Toxiproxy chaos injection test suite simulating 30% packet drops, artificial latency spikes, and network partitions across PostgreSQL and Redis; an Automated Invariant Verification engine proving 100% financial balance integrity with zero double-spends and zero orphaned debits across high-concurrency chaos runs; end-to-end OpenTelemetry distributed tracing propagating W3C traceparent contexts across HTTP requests, virtual threads, and saga hops; and an exportable SOC2/PCI-DSS compliant immutable audit log exporter (JSON/CSV) for all state mutations, dual-stage lock claims, operator interventions, and zombie fencing events."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Toxiproxy Network Chaos Injection Suite (Priority: P1) 🎯 MVP

As a Site Reliability Engineer (SRE) or platform resilience engineer, when certifying DeltaFlow for mission-critical banking environments, I want an automated network chaos injection testing suite capable of simulating extreme network degradation (such as 30% packet loss, artificial jitter, socket timeouts, and complete temporary partitions) against backend data and coordination stores, so that I can rigorously verify that the system detects failures, bounds latency, and never freezes threads or corrupts state under hostile infrastructure conditions.

**Why this priority**: Directly enforces Constitution Principle IV (Chaos-Verified). Financial orchestrators cannot rely on synthetic mock tests to discover distributed deadlocks, thread pool exhaustion, or lease race conditions under degraded network conditions.

**Independent Test**: Can be validated by executing the chaos test suite against containerized proxies. While injecting 30% packet loss and 800ms jitter between the application and storage infrastructure, transactions either complete successfully or fail gracefully via deterministic rollback, with zero unhandled exceptions, zero thread leaks, and complete recovery when network health is restored.

**Acceptance Scenarios**:

1. **Given** an active money transfer saga and an injected 30% packet loss on the persistence layer, **When** the saga executes forward steps, **Then** all timeouts and retries honor configured bounds, and either the transaction completes successfully or backward compensation is deterministically triggered.
2. **Given** an in-flight distributed lock attempt under a simulated 2-second network latency spike, **When** lock acquisition exceeds the safety lease window, **Then** the contender backs off without hanging the calling thread, avoiding cascading thread starvation.
3. **Given** a complete network partition between the orchestrator and external participants mid-saga, **When** the partition is healed, **Then** the background crash recovery scanner automatically detects halted leases and safely settles all orphaned instances.
4. **Given** a toxic proxy injection disconnecting active connections abruptly, **When** connection dropouts occur, **Then** the connection pools gracefully reconnect and resume operations without requiring an application restart.

---

### User Story 2 - Automated Financial Invariant Verification Engine (Priority: P1)

As a Chief Risk Officer (CRO) or lead financial auditor, when reviewing the results of high-concurrency stress and chaos testing runs, I want an automated financial invariant verification engine that cryptographically audits all account ledgers and journal records, so that I have mathematical proof that zero balance discrepancies, zero double-spends, and zero orphaned debits occurred across thousands of chaos-injected transactions.

**Why this priority**: Non-negotiable core principle (Constitution Principle I: Zero Double-Spend & Financial Invariance). In financial systems, surviving network chaos is insufficient if monetary balances drift or funds become stranded.

**Independent Test**: Can be verified by running a high-concurrency workload (thousands of concurrent transfers across diverse accounts while chaos faults are continuously injected). The invariant verification engine scans all accounts, debits, credits, and compensations, verifying that the global financial equation strictly balances: `Sum(Debits) == Sum(Credits) + Sum(Compensated Refunds)` with exactly 0.00 discrepancy.

**Acceptance Scenarios**:

1. **Given** a batch of 1,000+ concurrent transfer requests executed under continuous chaos injection, **When** the invariant verifier runs after all workflows settle, **Then** it proves that every debited account has an exact corresponding credit or verified backward refund.
2. **Given** any transaction that suffered a forced crash mid-execution, **When** audited by the invariant engine, **Then** no intermediate state exists where money was deducted from a sender without either arriving at the receiver or being refunded.
3. **Given** client retry attempts replaying duplicate idempotency keys during chaos, **When** audited, **Then** the invariant verifier confirms that no duplicate debit or credit was executed against any account.
4. **Given** an invariant audit run, **When** verification completes, **Then** the engine outputs a structured verification certificate detailing total transactions evaluated, settled count, compensated count, and zero invariant violations.

---

### User Story 3 - End-to-End OpenTelemetry Distributed Tracing (Priority: P2)

As a software engineer or operations investigator, when diagnosing a failed or high-latency multi-step transaction spanning multiple services, I want distributed tracing with standardized W3C context propagation across HTTP entry points, background threads, and saga step hops, so that I can visualize end-to-end trace waterfalls, isolate latency bottlenecks, and correlate log messages with unique trace identifiers.

**Why this priority**: Essential for observability and root-cause analysis (Constitution Principle V). Complex distributed transactions with retries, compensations, and outbox dispatches are impossible to debug without unified distributed traces.

**Independent Test**: Can be verified by initiating a transfer request containing an incoming W3C `traceparent` header. Inspecting the captured OpenTelemetry trace confirms that the root trace ID is maintained across HTTP ingress, asynchronous task scopes, transactional outbox publishing, and step compensation spans.

**Acceptance Scenarios**:

1. **Given** an incoming HTTP transfer request with a W3C `traceparent` header, **When** processed by DeltaFlow, **Then** the orchestrator extracts and adopts the trace context, linking all downstream database, cache, and RPC spans to the root trace.
2. **Given** a request without an existing trace header, **When** entering the system, **Then** DeltaFlow generates a compliant W3C trace ID and injects it into outbound HTTP and domain event headers.
3. **Given** a saga step executing across parallel virtual threads, **When** subtasks fork, **Then** OpenTelemetry context is reliably propagated into child virtual threads without loss of trace parentage.
4. **Given** a backward compensation triggered due to downstream failure, **When** viewing the trace in an OpenTelemetry-compatible collector, **Then** compensation spans are explicitly tagged with failure reasons and linked to the originating failed forward span.

---

### User Story 4 - SOC2/PCI-DSS Immutable Audit Trail Exporter (Priority: P2)

As an external security auditor or internal compliance officer, when conducting a periodic regulatory audit (SOC2 Type II or PCI-DSS), I want an exportable audit trail service and API that extracts tamper-evident, append-only logs in structured formats (JSON and CSV) covering all lifecycle events, so that our organization can demonstrate complete non-repudiation and forensic traceability for every monetary movement.

**Why this priority**: Compliance mandate (Constitution Principle V). Regulated financial applications must provide verifiable, exportable records of who authorized transactions, when locks were acquired, why manual interventions occurred, and when compensations settled.

**Independent Test**: Can be verified by querying the audit export endpoint with time ranges, event types, or tenant IDs. The exporter returns structured JSON or CSV streams containing chronological records with cryptographic integrity hashes, ensuring records cannot be altered retroactively without detection.

**Acceptance Scenarios**:

1. **Given** a compliance request for an audit period, **When** an authorized auditor requests an export via the audit API, **Then** the system produces a structured export (JSON or CSV) including all state transitions, dual-stage lock claims, operator interventions, and zombie fencing rejections.
2. **Given** exported audit records, **When** verified for cryptographic integrity, **Then** each record contains an immutable event hash chaining back to the previous record, proving no entries were deleted, reordered, or modified.
3. **Given** sensitive account data or credentials, **When** audit records are exported, **Then** all personally identifiable information (PII) and secret keys are securely masked or redacted according to PCI-DSS standards.
4. **Given** manual operator intervention events, **When** exported, **Then** the records include operator ID, mandatory justification, target saga, previous/new states, and precise timestamps.

---

### User Story 5 - Production Hardening & Operational Health Probes (Priority: P3)

As a DevOps engineer deploying DeltaFlow into a high-availability production Kubernetes cluster, I want production-grade health probes (liveness, readiness, and startup), graceful shutdown sequences, and connection pool saturation metrics, so that automated orchestration platforms can route traffic reliably and perform zero-downtime rolling deployments without interrupting in-flight transactions.

**Why this priority**: Production operational readiness. Systems handling financial operations must guarantee graceful draining of active requests and unambiguous health signaling during maintenance or rolling upgrades.

**Independent Test**: Can be verified by hitting standard actuator health endpoints (`/actuator/health/liveness`, `/actuator/health/readiness`) during normal operation and during simulated backend outages. Sending a `SIGTERM` signal to a running node with active in-flight sagas verifies that the process pauses incoming traffic, completes active write-ahead log flushes, and shuts down cleanly within a configured grace period.

**Acceptance Scenarios**:

1. **Given** a running application instance with healthy database and cache connections, **When** checked via readiness probes, **Then** the probe returns HTTP 200 OK indicating readiness to accept financial transactions.
2. **Given** a total loss of connectivity to the primary persistence store, **When** readiness is probed, **Then** the probe reports HTTP 503 Service Unavailable, preventing ingress load balancers from routing traffic to the impaired node.
3. **Given** an instance receiving a graceful termination signal (`SIGTERM`), **When** shutdown begins, **Then** the node rejects new ingress requests, permits existing in-flight sagas to complete or reach safe checkpoints within a 30-second grace window, and flushes transactional outbox buffers.
4. **Given** a high-throughput burst, **When** database or thread resources approach saturation, **Then** production metrics expose pool utilization warnings before thread starvation occurs.

---

### Edge Cases

- **Toxiproxy Cascading Timeout**: What happens when network latency is injected right at the boundary of a step's timeout window? The orchestrator must treat ambiguous timeouts as potential failures, trigger idempotent compensation, and fence any subsequent delayed response from committing.
- **Split-Brain Network Partition**: What happens when an instance is isolated from the database while holding an in-flight saga? The lease must expire in the database, allowing another recovery worker to take over, while the isolated worker's late write is rejected via monotonic fencing tokens.
- **Clock Drift Across Distributed Nodes**: How does the system handle subtle system clock disparities during invariant checking and audit log sorting? The system must rely on monotonic sequences and server-assigned database timestamps rather than uncoordinated client clocks.
- **Massive Audit Export Payloads**: What happens when an auditor requests a multi-gigabyte audit export spanning several months? The export endpoint must stream data reactively using chunked transfer encoding rather than loading the entire result set into server memory.
- **Chaos Injection During Backward Compensation**: What happens when Toxiproxy drops packets specifically while executing a compensation refund step? The orchestrator must record the compensation failure in the WAL and re-attempt compensation via the crash recovery scanner until settled.

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST integrate an automated Toxiproxy chaos injection harness capable of introducing configurable network latency (100ms–2000ms), jitter, packet loss (10%–50%), and full TCP connection cuts against PostgreSQL and Redis containers.
- **FR-002**: System MUST execute automated chaos test suites validating that all saga workflows either reach successful completion or cleanly roll back via backward compensation during network degradation.
- **FR-003**: System MUST provide an Automated Invariant Verification engine that checks financial ledger balance integrity across all accounts involved in transactions: `Total Debits == Total Credits + Total Refunds`.
- **FR-004**: System MUST verify zero double-spends and zero orphaned deductions across high-concurrency chaos runs.
- **FR-005**: Invariant Verification engine MUST generate a verifiable execution report summarizing transaction volume, success count, compensated count, and zero invariant violations.
- **FR-006**: System MUST integrate OpenTelemetry distributed tracing and propagate standard W3C `traceparent` headers across all incoming HTTP requests, asynchronous outbox dispatchers, and virtual thread execution scopes.
- **FR-007**: System MUST record distinct OpenTelemetry spans for each saga step, capturing step ordinal, step name, fencing token, execution outcome, and failure reason without leaking sensitive financial credentials.
- **FR-008**: System MUST capture OpenTelemetry metrics for saga execution duration, step retry attempts, circuit breaker state changes, and outbox delivery lag.
- **FR-009**: System MUST provide a secure audit export endpoint (`GET /api/v1/audit/export`) capable of streaming audit logs in structured JSON and CSV formats.
- **FR-010**: Audit export MUST support filtering by date range, tenant identifier, correlation key, and event type.
- **FR-011**: Exported audit records MUST include an append-only cryptographic sequence or SHA-256 integrity hash confirming that log entries have not been altered, omitted, or reordered.
- **FR-012**: Audit records MUST sanitize and mask sensitive customer data (such as raw account credentials or full payment details) in compliance with PCI-DSS guidelines.
- **FR-013**: System MUST provide Kubernetes-compatible liveness (`/actuator/health/liveness`) and readiness (`/actuator/health/readiness`) health endpoints reflecting the operational status of database, Redis, and circuit breaker components.
- **FR-014**: System MUST support graceful application shutdown, honoring a configurable draining period where active write-ahead log operations complete before process exit.
- **FR-015**: System MUST log all chaos test outcomes and invariant verification assertions in machine-readable JSON format for automated CI/CD gating.
- **FR-016**: System MUST enforce compile-time exhaustion checks on all new chaos and invariant domain models using Java 21 sealed interfaces and record patterns per Constitution Principle III.

---

### Key Entities

- **ChaosSimulation**: Defines a chaos injection test scenario, including target component (PostgreSQL, Redis, External API), toxic type (latency, jitter, packet loss, cut), duration, and intensity parameters.
- **InvariantVerificationReport**: The immutable audit report generated after a high-concurrency or chaos test run, detailing total transactions audited, total debited amount, total credited amount, total compensated amount, discrepancy amount (must equal 0.00), and invariant check status (`PASSED` or `VIOLATED`).
- **DistributedTraceRecord**: Internal model representing distributed tracing correlation data, holding `traceId`, `spanId`, `parentSpanId`, `sagaId`, `tenantId`, and `operationName`.
- **AuditExportRecord**: The normalized, sanitized log entity serialized during audit log export (JSON/CSV), containing `eventId`, `eventType`, `tenantId`, `correlationKey`, `timestamp`, `operatorId` (if manual intervention), `payloadHash`, `eventDetails`, and `recordChecksum`.
- **SystemHealthStatus**: The composite health state evaluated by liveness/readiness probes, encapsulating database connectivity, cache response time, and circuit breaker availability.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: **100% Financial Balance Invariant Integrity**: Zero balance discrepancies across high-concurrency chaos runs (`Discrepancy == 0.00` across all source, target, and refund balances).
- **SC-002**: **Zero Double-Spend Under Chaos**: 0 duplicate debits or credits detected when client retries and network packet loss are injected simultaneously.
- **SC-003**: **Resilience Under 30% Packet Loss**: System completes or safely compensates 100% of initiated transactions under 30% network packet loss without leaving transactions in permanent ambiguous states.
- **SC-004**: **End-to-End Trace Context Continuity**: 100% of executed saga steps and outbox dispatch events maintain the originating W3C trace ID across asynchronous thread boundaries.
- **SC-005**: **Tamper-Evident Audit Export Performance**: Audit export streams at least 5,000 records per second in JSON/CSV formats with cryptographic integrity validation and full PCI-DSS data redaction.
- **SC-006**: **Zero-Downtime Graceful Termination**: Application completes in-flight WAL persistence and drains active transactions within a 30-second window upon receiving termination signals with zero data corruption.

---

## Assumptions

- **Toxiproxy Infrastructure**: Toxiproxy will be provisioned alongside PostgreSQL and Redis containers using Testcontainers during automated integration test execution.
- **OpenTelemetry Export**: While traces are propagated and formatted according to W3C OpenTelemetry specifications, local verification tests will use an in-memory or mock tracer/collector to validate span creation and context continuity without requiring an external Jaeger/Zipkin instance.
- **Audit Log Storage**: Audit logs originate from the append-only audit trail infrastructure established in Milestone 2 (`audit_logs` / `domain_events`) and are streamed reactively for export.
- **Account Ledger Balances**: Invariant testing uses deterministic initial balances for test accounts, allowing exact mathematical summation of all credits, debits, and compensation refunds.
- **Graceful Shutdown Bounds**: A 30-second maximum draining timeout is acceptable for container environments before SIGKILL is dispatched by orchestrators.
