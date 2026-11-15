# Implementation Plan: Operator Management Dashboard

**Branch**: `005-operator-dashboard` | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-operator-dashboard/spec.md`

## Summary

Build an operational management dashboard featuring an interactive real-time Saga Flow DAG visualizer with dynamic color-coded execution states, a forensic Idempotency Inspector to audit duplicate requests and detect payload tampering, a safe Manual Intervention Console to recover halted transactions, and a Server-Sent Events (SSE) live streaming engine in Spring Boot delivering sub-second state broadcasts.

## Technical Context

**Language/Version**: Java 21 LTS (Backend) & TypeScript 5.5+ (Frontend)  
**Primary Dependencies**: Spring Boot 3.3.5 (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`), Resilience4j 2.2.0, React 18, `@xyflow/react` (React Flow), Vite, Tailwind CSS, Lucide icons  
**Storage**: PostgreSQL 16 (`saga_instances`, `saga_steps`, `idempotency_records`, `outbox_events`), Redis 7 (Distributed locking & lease coordination)  
**Testing**: JUnit 5, AssertJ, TestRestTemplate, Testcontainers (PostgreSQL 16, Redis 7)  
**Target Platform**: Linux / Containerized Cloud deployment, Modern Desktop Browser (Chrome/Edge/Firefox)  
**Project Type**: Full-Stack Distributed Systems Application (Spring Boot API/SSE Engine + React TypeScript SPA)  
**Performance Goals**: <500ms live stream state update latency; sub-second forensic search across 100,000+ records; 60 FPS interactive DAG navigation  
**Constraints**: Zero double-spend safety gates (reject intervention if worker lease is active); mandatory operator justification notes; immutable audit logging  
**Scale/Scope**: 4 primary views (Interactive DAG Visualizer, Sagas Table, Idempotency Inspector, Telemetry Monitor) + 5 backend REST/SSE endpoints  

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **Principle I: Zero Double-Spend & Financial Invariance**
  - *Status*: **PASS**
  - *Enforcement*: The Manual Intervention Console validates that a target saga instance has an expired lease before accepting any force-settle or force-retry command. If a worker lease is still active, the command is rejected with HTTP 409 Conflict.

- **Principle II: Strict Backward Compensation & Zombie-Proofing**
  - *Status*: **PASS**
  - *Enforcement*: Manual intervention settling transitions halted sagas into terminal `COMPENSATED` state. Step retries generate an incremented fencing token, preventing stale zombie responses from corrupting state.

- **Principle III: Compile-Time Exhaustion & Type Safety**
  - *Status*: **PASS**
  - *Enforcement*: State projections, intervention actions, and live event types are implemented using Java 21 sealed interfaces, records, and pattern matching.

- **Principle IV: Test-Driven & Chaos-Verified**
  - *Status*: **PASS**
  - *Enforcement*: Contract and integration tests are authored before production endpoints, verified against real containerized PostgreSQL and Redis instances using Testcontainers.

- **Principle V: Observability & Immutable Audit Trail**
  - *Status*: **PASS**
  - *Enforcement*: Every intervention generates an immutable `OPERATOR_INTERVENTION` audit event in structured JSON containing operator ID, justification note, and timestamps.

---

## Project Structure

### Documentation (this feature)

```text
specs/005-operator-dashboard/
├── spec.md              # Feature specification
├── plan.md              # Implementation plan (this file)
├── research.md          # Technical research & architectural decisions
├── data-model.md        # Graph, intervention, forensic, and telemetry data models
├── contracts/
│   └── dashboard-api.yaml # OpenAPI 3.0 specification for dashboard endpoints
├── quickstart.md        # End-to-end validation scenarios
├── checklists/
│   └── requirements.md  # Specification quality checklist
└── tasks.md             # Implementation tasks (generated via /speckit-tasks)
```

### Source Code Architecture

```text
# Backend (Spring Boot 3 / Java 21)
src/main/java/io/deltaflow/
├── dashboard/
│   ├── api/
│   │   ├── DashboardApiController.java      # REST endpoints (sagas, graph, intervention, forensics, telemetry)
│   │   └── DashboardStreamController.java   # SSE live streaming endpoint (/api/v1/dashboard/stream)
│   ├── dto/
│   │   ├── SagaSummaryDto.java              # Saga list summary
│   │   ├── SagaGraphDto.java                # DAG graph projection with nodes & edges
│   │   ├── SagaNodeDto.java                 # DAG step node
│   │   ├── SagaEdgeDto.java                 # DAG transition edge
│   │   ├── OperatorInterventionRequest.java # Intervention command
│   │   ├── OperatorInterventionResult.java  # Intervention outcome
│   │   ├── IdempotencyForensicDto.java      # Idempotency audit view
│   │   └── TelemetrySummaryDto.java         # System health counters & circuit breaker states
│   └── service/
│       ├── DashboardService.java            # Graph projection, forensic search, & telemetry aggregation
│       ├── DashboardEventBroadcaster.java   # SseEmitter manager broadcasting live state machine events
│       └── OperatorInterventionService.java # Safe intervention execution with lease & fencing validation
└── saga/
    └── service/
        └── SagaCoordinator.java             # Emits state events to DashboardEventBroadcaster

# Frontend (React 18 / TypeScript / Vite / Tailwind CSS)
frontend/
├── package.json
├── vite.config.ts                           # Proxies /api to localhost:8080
├── tsconfig.json
├── tailwind.config.js
├── src/
│   ├── App.tsx                              # Main navigation & dashboard layout
│   ├── main.tsx
│   ├── components/
│   │   ├── dag/
│   │   │   ├── SagaFlowDag.tsx              # React Flow interactive DAG visualizer
│   │   │   ├── StepNodeCard.tsx             # Custom color-coded step card with timers
│   │   │   └── StepDetailDrawer.tsx         # Node inspector drawer (payloads, fencing, errors)
│   │   ├── intervention/
│   │   │   └── InterventionModal.tsx        # Force-retry & force-compensate dialog with reason input
│   │   ├── forensics/
│   │   │   └── IdempotencyInspector.tsx     # Key, hash, and tampering forensic table
│   │   └── telemetry/
│   │       └── TelemetryHeader.tsx          # Real-time metrics, DLQ alert, circuit breakers
│   ├── hooks/
│   │   └── useDashboardStream.ts            # SSE event stream subscription & state reconciliation
│   ├── services/
│   │   └── dashboardApi.ts                  # REST API client
│   └── types/
│       └── dashboard.ts                     # TypeScript interfaces matching OpenAPI schema

# Tests
src/test/java/io/deltaflow/dashboard/
└── integration/
    ├── DashboardApiIntegrationTest.java     # Verifies REST graph, telemetry, & forensic endpoints
    ├── DashboardStreamIntegrationTest.java  # Verifies SSE live event delivery
    └── OperatorInterventionIntegrationTest.java # Verifies lease check, fencing, & audit logging
```
