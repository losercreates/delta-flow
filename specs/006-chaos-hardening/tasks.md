# Tasks: Chaos Testing, OpenTelemetry & Production Hardening

**Input**: Design documents from `/specs/006-chaos-hardening/`  
**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/)  
**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (`[US1]`, `[US2]`, `[US3]`, `[US4]`, `[US5]`)
- Exact file paths included in all descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependency additions, build configurations, and baseline properties for Toxiproxy, OpenTelemetry, and Actuator

- [x] T001 Add `org.testcontainers:toxiproxy`, `eu.rekawek.toxiproxy:toxiproxy-java`, `io.opentelemetry:opentelemetry-api`, and `io.opentelemetry:opentelemetry-sdk` dependencies in `pom.xml`
- [x] T002 Configure Spring Boot Actuator health endpoints, Kubernetes probes (`liveness`, `readiness`), and graceful shutdown timeout (30s) in `src/main/resources/application.yml`
- [x] T003 [P] Configure test profile overrides for OpenTelemetry and Actuator in `src/test/resources/application-test.yml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core DTOs, domain records, and base tracing utilities required across all user stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 [P] Create OpenTelemetry tracing wrapper records `TraceContextMetadata.java` and `TraceContextHolder.java` supporting virtual thread inheritance in `src/main/java/io/deltaflow/observability/tracing/`
- [x] T005 [P] Create financial invariant verification domain records `InvariantVerificationReport.java`, `InvariantViolation.java`, and `AccountBalanceSnapshot.java` in `src/main/java/io/deltaflow/chaos/domain/`
- [x] T006 [P] Create audit export records `AuditExportRecord.java`, `AuditExportFormat.java`, and `AuditExportRequest.java` in `src/main/java/io/deltaflow/audit/dto/`
- [x] T007 [P] Create composite health projection record `ProductionHealthDetails.java` in `src/main/java/io/deltaflow/observability/health/`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Toxiproxy Network Chaos Injection Suite (Priority: P1) 🎯 MVP

**Goal**: Build an automated network chaos testing harness utilizing Toxiproxy containers to validate that DeltaFlow workflows cleanly settle or roll back without thread leaks under 30% packet loss and latency jitter.

**Independent Test**: `mvn test -Dtest=ToxiproxyNetworkChaosIntegrationTest` verifies that high-concurrency transfers subjected to 30% packet loss and 500ms latency jitter against PostgreSQL and Redis either complete successfully or execute backward compensation, with zero frozen threads.

### Tests for User Story 1 ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T008 [P] [US1] Integration test verifying saga behavior under 30% packet loss and latency spikes in `src/test/java/io/deltaflow/chaos/integration/ToxiproxyNetworkChaosIntegrationTest.java`

### Implementation for User Story 1

- [x] T009 [US1] Implement Toxiproxy container support helper and proxy manager `ToxiproxyHarness.java` managing proxy ports for PostgreSQL and Redis in `src/test/java/io/deltaflow/chaos/support/ToxiproxyHarness.java`
- [x] T010 [US1] Implement simulated toxic scenarios (latency jitter, packet drop, connection cutoff) in `src/test/java/io/deltaflow/chaos/support/ChaosScenarios.java`
- [x] T011 [US1] Integrate connection timeout and retry boundary safeguards in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java` to prevent thread starvation during prolonged persistence latency

**Checkpoint**: User Story 1 is fully functional and independently verified (MVP delivered).

---

## Phase 4: User Story 2 - Automated Financial Invariant Verification Engine (Priority: P1)

**Goal**: Provide an automated double-entry ledger invariant verification engine that mathematically proves `Total Debits == Total Credits + Total Refunds` with zero discrepancy and zero double-spends across high-concurrency chaos runs.

**Independent Test**: `mvn test -Dtest=FinancialInvariantVerificationTest` dispatches 1,000 concurrent transfers under chaos, executes the invariant verification engine, and verifies that `discrepancyAmount == 0.00` with 0 double-spends and 0 orphaned debits.

### Tests for User Story 2 ⚠️

- [x] T012 [P] [US2] Integration test verifying ledger balance conservation and zero double-spend detection in `src/test/java/io/deltaflow/chaos/integration/FinancialInvariantVerificationTest.java`

### Implementation for User Story 2

- [x] T013 [US2] Implement `FinancialInvariantVerifier.java` scanning account debits, credits, and compensation refunds to assert balance conservation in `src/main/java/io/deltaflow/chaos/service/FinancialInvariantVerifier.java`
- [x] T014 [US2] Implement `GET /api/v1/invariants/verify` endpoint returning `InvariantVerificationReport` in `src/main/java/io/deltaflow/chaos/api/InvariantVerificationController.java`
- [x] T015 [US2] Add double-spend detection logic checking for duplicate idempotency key executions in `src/main/java/io/deltaflow/chaos/service/FinancialInvariantVerifier.java`

**Checkpoint**: User Stories 1 AND 2 work independently.

---

## Phase 5: User Story 3 - End-to-End OpenTelemetry Distributed Tracing (Priority: P2)

**Goal**: Propagate standardized W3C `traceparent` headers across HTTP requests, virtual threads, saga steps, and transactional outbox events to deliver end-to-end distributed trace continuity.

**Independent Test**: `mvn test -Dtest=W3CTracePropagationIntegrationTest` sends a request with an incoming W3C `traceparent` header, executes a multi-step saga, and asserts that all step journal records and outbox domain events preserve the originating trace ID.

### Tests for User Story 3 ⚠️

- [x] T016 [P] [US3] Integration test verifying W3C `traceparent` extraction, virtual thread context propagation, and outbox event trace tagging in `src/test/java/io/deltaflow/observability/integration/W3CTracePropagationIntegrationTest.java`

### Implementation for User Story 3

- [x] T017 [US3] Implement `W3CTraceContextFilter.java` extracting incoming `traceparent` headers or generating new W3C trace IDs in `src/main/java/io/deltaflow/observability/filter/W3CTraceContextFilter.java`
- [x] T018 [US3] Implement `DeltaFlowTracer.java` wrapping OpenTelemetry `Tracer` to create and close spans for saga steps in `src/main/java/io/deltaflow/observability/tracing/DeltaFlowTracer.java`
- [x] T019 [US3] Integrate `DeltaFlowTracer` into `SagaCoordinator.java` to record step spans and propagate trace context to parallel virtual threads in `src/main/java/io/deltaflow/saga/service/SagaCoordinator.java`
- [x] T020 [US3] Inject active `traceparent` into outbox domain event metadata in `src/main/java/io/deltaflow/outbox/service/OutboxService.java`

**Checkpoint**: User Stories 1, 2, and 3 are functional.

---

## Phase 6: User Story 4 - SOC2/PCI-DSS Immutable Audit Trail Exporter (Priority: P2)

**Goal**: Provide a high-performance streaming export endpoint (`GET /api/v1/audit/export`) serving tamper-evident audit logs in NDJSON and CSV formats with SHA-256 block hash chaining and PCI-DSS data redaction.

**Independent Test**: `mvn test -Dtest=AuditExportIntegrationTest` requests `/api/v1/audit/export?format=JSON` and `/api/v1/audit/export?format=CSV`, verifying streaming output, cryptographic hash chain validity ($H_i = \text{SHA256}(H_{i-1} \parallel \dots)$), and primary account number (PAN) masking.

### Tests for User Story 4 ⚠️

- [x] T021 [P] [US4] Integration test verifying NDJSON/CSV streaming, cryptographic hash chaining, and PCI-DSS masking in `src/test/java/io/deltaflow/audit/integration/AuditExportIntegrationTest.java`

### Implementation for User Story 4

- [x] T022 [US4] Implement `PciDssMaskingService.java` sanitizing account numbers (e.g. `****-****-****-1234`) and redacting credentials in `src/main/java/io/deltaflow/audit/service/PciDssMaskingService.java`
- [x] T023 [US4] Implement `AuditLogExporter.java` with SHA-256 hash chaining and reactive streaming serialization in `src/main/java/io/deltaflow/audit/service/AuditLogExporter.java`
- [x] T024 [US4] Implement `AuditExportController.java` handling `GET /api/v1/audit/export` with chunked transfer encoding in `src/main/java/io/deltaflow/audit/api/AuditExportController.java`

**Checkpoint**: User Stories 1, 2, 3, and 4 are functional.

---

## Phase 7: User Story 5 - Production Hardening & Operational Health Probes (Priority: P3)

**Goal**: Implement Kubernetes-ready liveness/readiness health indicators and a graceful shutdown coordinator that drains active WAL transitions within 30 seconds before process exit.

**Independent Test**: `mvn test -Dtest=ProductionHealthProbesIntegrationTest` queries `/actuator/health/liveness` and `/actuator/health/readiness`, simulates database outages to verify readiness de-registration, and verifies graceful shutdown draining.

### Tests for User Story 5 ⚠️

- [x] T025 [P] [US5] Integration test verifying actuator health indicators, readiness de-registration on failure, and graceful draining in `src/test/java/io/deltaflow/observability/integration/ProductionHealthProbesIntegrationTest.java`

### Implementation for User Story 5

- [x] T026 [US5] Implement `DatabaseHealthIndicator.java` checking PostgreSQL response time in `src/main/java/io/deltaflow/observability/health/DatabaseHealthIndicator.java`
- [x] T027 [US5] Implement `RedisLockHealthIndicator.java` checking Redis latency in `src/main/java/io/deltaflow/observability/health/RedisLockHealthIndicator.java`
- [x] T028 [US5] Implement `GracefulShutdownHandler.java` coordinating in-flight WAL completion upon `SIGTERM` in `src/main/java/io/deltaflow/observability/health/GracefulShutdownHandler.java`

**Checkpoint**: All user stories functional.

---

## Phase 8: Polish & Full Regression

**Purpose**: Cross-cutting verification, documentation updates, and complete test suite validation

- [x] T029 Execute all quickstart validation scenarios from `specs/006-chaos-hardening/quickstart.md`
- [x] T030 Run full regression test suite `mvn test -B` with Testcontainers verifying all Milestones 1–6 pass cleanly
- [x] T031 Validate constitution compliance across all new packages and update OpenAPI documentation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion - BLOCKS all user stories
- **User Stories (Phases 3–7)**: All depend on Phase 2 completion
  - User Story 1 (P1): Toxiproxy Network Chaos Injection Suite (MVP)
  - User Story 2 (P1): Automated Financial Invariant Verification Engine
  - User Story 3 (P2): End-to-End OpenTelemetry Distributed Tracing
  - User Story 4 (P2): SOC2/PCI-DSS Immutable Audit Trail Exporter
  - User Story 5 (P3): Production Hardening & Operational Health Probes
- **Polish (Phase 8)**: Depends on all user stories being complete

---

## Parallel Opportunities

- **Phase 1**: T003 can execute in parallel with T002.
- **Phase 2**: T004, T005, T006, and T007 can execute in parallel.
- **Phase 3 (US1)**: T008 (test) and T009/T010 (support harness) can execute in parallel.
- **Phase 4 (US2)**: T012 (test) and T014 (controller) can execute in parallel with T013 (service).
- **Phase 5 (US3)**: T016 (test) and T017 (filter) can execute in parallel with T018 (tracer).
- **Phase 6 (US4)**: T021 (test) and T022 (masking) can execute in parallel with T024 (controller).
- **Phase 7 (US5)**: T025 (test), T026 (DB probe), and T027 (Redis probe) can execute in parallel.

---

## Implementation Strategy

### MVP First (User Story 1 Only)
1. Complete Phase 1: Setup (`pom.xml` dependencies, Actuator configs)
2. Complete Phase 2: Foundational (DTOs, domain records)
3. Complete Phase 3: User Story 1 (`ToxiproxyNetworkChaosIntegrationTest`, `ToxiproxyHarness.java`)
4. Validate with `mvn test -Dtest=ToxiproxyNetworkChaosIntegrationTest` (Delivers MVP Chaos Testing!)

### Incremental Delivery
1. Add Phase 4 (US2: Financial Invariant Verification) -> Proves mathematical 0.00 discrepancy
2. Add Phase 5 (US3: OpenTelemetry Distributed Tracing) -> W3C `traceparent` propagation
3. Add Phase 6 (US4: SOC2/PCI-DSS Audit Trail Exporter) -> Tamper-evident streaming NDJSON/CSV
4. Add Phase 7 (US5: Production Hardening) -> Kubernetes probes & graceful draining
5. Complete Phase 8: Polish & Full Regression Suite (`mvn test -B`)
