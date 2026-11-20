# Implementation Plan: Real-Time Streaming & Chaos Control in Operator Dashboard

**Branch**: `009-dashboard-chaos-control` | **Date**: 2026-11-20 | **Spec**: [spec.md](file:///home/mayukh/projects/delta-flow/specs/009-dashboard-chaos-control/spec.md)

**Input**: Feature specification from `specs/009-dashboard-chaos-control/spec.md`

---

## Summary

Enhance the DeltaFlow Operator Dashboard with comprehensive real-time streaming and chaos control capabilities:
1. **Server-Sent Events (SSE) Telemetry**: Real-time event broadcasting for all saga lifecycle transitions (including `SUSPENDED`, `RESUMED`, `TIMED_OUT`, `COMPENSATED`), with heartbeat keep-alives and automatic client-side reconnection.
2. **Interactive Chaos Control Console**: REST endpoints and React UI controls to inject network latency, jitter, packet loss, and connection cuts via Toxiproxy, alongside direct Resilience4j circuit breaker state manipulations (`OPEN`, `CLOSED`, `HALF_OPEN`).
3. **One-Click Financial Invariant Verification**: Interactive dashboard interface to trigger on-demand audits using `FinancialInvariantVerifier`, proving zero double-spend, zero orphaned debits, and mathematical conservation of debits, credits, and refunds.
4. **DLQ Forensics & Operator Intervention**: Outbox Dead-Letter Queue inspection and safe event replay, plus manual force-compensation controls with mandatory operator justifications and immutable audit logging.

---

## Technical Context

**Language/Version**: Java 21 LTS (Virtual Threads / Project Loom), TypeScript 5.2+ (React 18)  
**Primary Dependencies**: Spring Boot 3.3.5, Spring Data JPA, Resilience4j (`resilience4j-circuitbreaker`), Toxiproxy Client (`eu.rekawek.toxiproxy`), React 18, Vite, Lucide React, React Flow / Tailwind CSS  
**Storage**: PostgreSQL 16 (`saga_instances`, `saga_steps`, `idempotency_records`, `outbox_events`), Redis 7 (distributed locks)  
**Testing**: JUnit 5, AssertJ, Testcontainers (PostgreSQL, Redis, Kafka, Toxiproxy)  
**Target Platform**: Linux server (JVM 21) & Modern Evergreen Web Browsers (Chrome / Firefox / Safari)  
**Project Type**: Distributed Web Service & Single-Page Application (SPA)  
**Performance Goals**: SSE event broadcast latency $<500\text{ms}$; Invariant verification scan $<3\text{s}$ for up to 10,000 transactions; Chaos fault injection/healing execution $<1\text{s}$  
**Constraints**: Zero double-spend & strict financial balance conservation; LIFO deterministic compensation order; mandatory operator attribution and immutable audit logging; zero memory leaks on long-lived SSE connections  
**Scale/Scope**: Multi-tenant support, concurrent operator sessions, automated reconnection resilience  

---

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evaluation & Architectural Guarantee |
| :--- | :---: | :--- |
| **I. Zero Double-Spend & Financial Invariance** | **PASS** | Chaos testing and operator interventions operate strictly through idempotency guards and saga coordinator contracts. Invariant verifier arithmetically checks $\sum \text{Debits} = \sum \text{Credits} + \sum \text{Refunds}$. |
| **II. Strict Backward Compensation & Zombie-Proofing** | **PASS** | Force-compensation executes strictly in LIFO order with fencing token checks; dead-letter replays re-verify idempotency locks. |
| **III. Compile-Time Exhaustion & Type Safety** | **PASS** | Live event types, chaos actions, and circuit breaker states are modeled using Java 21 sealed interfaces and records, enforcing exhaustive pattern matching. |
| **IV. Test-Driven & Chaos-Verified** | **PASS** | Full integration test coverage using Testcontainers (PostgreSQL, Redis, Kafka, Toxiproxy). Automated tests verify invariant preservation under 30% packet loss and 500ms latency. |
| **V. Observability & Immutable Audit Trail** | **PASS** | All chaos injections, fault removals, circuit breaker state toggles, and DLQ replays are recorded in immutable audit tables with operator IDs and timestamps. |

---

## Project Structure

### Documentation (this feature)

```text
specs/009-dashboard-chaos-control/
├── spec.md              # Feature specification
├── plan.md              # This implementation plan
├── research.md          # Technical research & architectural decisions
├── data-model.md        # Data entities, DTOs & state machine transitions
├── quickstart.md        # Verification and testing scenarios
├── contracts/
│   └── chaos-dashboard-api.yaml # OpenAPI 3.0 specification
└── checklists/
    └── requirements.md  # Quality validation checklist
```

### Source Code Layout

```text
src/
├── main/java/io/deltaflow/
│   ├── chaos/
│   │   ├── api/
│   │   │   ├── ChaosControlController.java      # Chaos injection & circuit breaker endpoints
│   │   │   └── InvariantVerificationController.java # Invariant audit endpoint
│   │   ├── domain/
│   │   │   ├── ChaosInjectionRequest.java       # Chaos request DTO
│   │   │   ├── ChaosStatusDto.java              # Proxy & circuit breaker status
│   │   │   ├── CircuitBreakerTransitionRequest.java # Circuit breaker override DTO
│   │   │   ├── InvariantVerificationReport.java # Audit report model
│   │   │   └── InvariantViolation.java          # Violation detail
│   │   └── service/
│   │       ├── ChaosControlService.java         # Toxiproxy & Resilience4j bridge
│   │       └── FinancialInvariantVerifier.java  # Balance & double-spend arithmetic auditor
│   ├── dashboard/
│   │   ├── api/
│   │   │   ├── DashboardApiController.java      # Sagas, DLQ & intervention endpoints
│   │   │   └── DashboardStreamController.java   # SSE live streaming endpoint
│   │   ├── dto/
│   │   │   ├── DashboardLiveEvent.java          # Event broadcast record
│   │   │   ├── DeadLetterOutboxEventDto.java    # DLQ event DTO
│   │   │   ├── DlqReplayRequest.java            # DLQ replay request DTO
│   │   │   └── TelemetrySummaryDto.java         # Top-level telemetry
│   │   └── service/
│   │       ├── DashboardEventBroadcaster.java   # SSE connection & broadcast manager
│   │       ├── DashboardService.java            # Read queries & DLQ aggregation
│   │       └── OperatorInterventionService.java # Intervention & force-compensation engine
│   └── outbox/
│       └── service/
│           └── OutboxPublisher.java             # Outbox worker with DLQ routing
└── test/java/io/deltaflow/
    ├── chaos/
    │   ├── api/
    │   │   └── ChaosControlIntegrationTest.java # API verification for chaos & circuit breakers
    │   └── integration/
    │       └── ToxiproxyNetworkChaosIntegrationTest.java
    └── dashboard/
        └── DashboardStreamIntegrationTest.java  # SSE broadcast & DLQ replay integration tests

frontend/src/
├── App.tsx                                      # Main container with 3 tabs (Visualizer, Idempotency, Chaos)
├── components/
│   ├── chaos/
│   │   ├── ChaosControlDashboard.tsx            # Main Chaos & Invariants tab
│   │   ├── ChaosInjectorPanel.tsx               # Fault injection controls (latency, drop)
│   │   ├── CircuitBreakerConsole.tsx            # Circuit breaker state cards & toggles
│   │   ├── InvariantAuditPanel.tsx              # One-click invariant verification card
│   │   └── DlqInspectorModal.tsx                # Dead-letter queue inspection & replay
│   ├── dag/
│   │   └── SagaFlowDag.tsx                      # Visual DAG with suspended/resumed highlights
│   ├── forensics/
│   │   └── IdempotencyInspector.tsx             # Idempotency key lookup
│   └── telemetry/
│       └── TelemetryHeader.tsx                  # Top telemetry bar with live SSE indicator
├── services/
│   ├── chaosApi.ts                              # API client for chaos & invariants
│   └── dashboardApi.ts                          # API client for sagas, telemetry & DLQ
└── types/
    ├── chaos.ts                                 # Chaos & invariant TypeScript definitions
    └── dashboard.ts                             # Dashboard & telemetry TypeScript definitions
```

**Structure Decision**: Monorepo layout with backend Spring Boot service (`src/main/java`) and Vite + React TypeScript single-page application (`frontend/src`).

---

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| :--- | :--- | :--- |
| None | All designs strictly follow Constitution and Milestone constraints | N/A |
