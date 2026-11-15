# Research & Technical Decisions: Operator Management Dashboard

## Decision 1: Real-Time Streaming Protocol (Server-Sent Events vs WebSocket)

### Decision
Use **Server-Sent Events (SSE)** via Spring MVC `SseEmitter` for real-time live event streaming from backend to dashboard (`GET /api/v1/dashboard/stream`), paired with standard REST API endpoints for commands (e.g. manual interventions, forensic queries).

### Rationale
- **Simplicity & Reliability**: SSE operates over standard HTTP/1.1 and HTTP/2 connections without requiring full-duplex protocol upgrades, STOMP broker relays, or extra TCP port management.
- **Native Browser Auto-Reconnect**: Browser `EventSource` provides automatic reconnection with exponential backoff and last-event-id tracking natively.
- **Asymmetric Traffic Fit**: Dashboard operations are 99% server-to-client telemetry (saga transitions, step progress, outbox events, circuit breaker states). Operator commands are discrete HTTP requests that benefit from standard REST semantics, HTTP status codes, and request validation.

### Alternatives Considered
- **Raw WebSockets / STOMP**: Higher connection setup complexity, requires WebSocket configuration, message brokers, and custom heartbeat/reconnection logic. Overkill for single-server operational dashboard.
- **Polling (Short/Long)**: Unnecessary database and HTTP overhead; introduces latency jitter unsuitable for live DAG step animation.

---

## Decision 2: Interactive DAG Visualizer Engine

### Decision
Adopt **React Flow (`@xyflow/react`)** with a custom Dagre auto-layout layout engine.

### Rationale
- **Component-Driven Nodes**: React Flow renders nodes as standard React components, making it straightforward to build custom `SagaStepNode` cards featuring live color-coded status badges, step icons, execution timers, and inline retry counters.
- **Branch Topology**: Effortlessly represents sequential pipelines, parallel split/join branches (Java 21 Structured Concurrency), and backward compensation rollback paths.
- **High Performance**: Native hardware-accelerated canvas/SVG viewport with smooth panning, zooming, and mini-map navigation.

### Alternatives Considered
- **Cytoscape.js**: Powerful graph library, but requires imperative canvas manipulation outside React's declarative lifecycle.
- **Mermaid.js / Pure SVG**: Static or read-only; lacks smooth interactive node clicking, state transitions, and custom drawer bindings.

---

## Decision 3: Frontend Architecture & Packaging

### Decision
Scaffold a decoupled **React 18 + TypeScript + Vite + Tailwind CSS** project in `/frontend`. Configure Vite dev proxy to forward `/api` requests to Spring Boot on `localhost:8080`. Support building static assets directly into Spring Boot's `src/main/resources/static` for single-jar production deployment.

### Rationale
- **Fast Developer Feedback**: Vite provides sub-second Hot Module Replacement (HMR).
- **Type Safety**: TypeScript shared API contracts and DTO types ensure compile-time synchronization between frontend components and backend REST/SSE payloads.
- **Flexible Deployment**: Enables running frontend and backend independently in development, while allowing a unified monolithic JAR distribution for easy operations.

### Alternatives Considered
- **Server-Side Rendered (Thymeleaf / JSP)**: Poor interactivity, impossible to achieve 60 FPS live DAG rendering or dynamic node inspection without heavy custom JavaScript.
- **Next.js**: Unnecessary SSR server complexity for an internal operational management dashboard.

---

## Decision 4: Safe Manual Intervention & Zombie Fencing

### Decision
Manual intervention operations (`FORCE_COMPENSATE`, `FORCE_RETRY`) must enforce strict safety gates:
1. **Active Lease Check**: If the target saga instance is in `EXECUTING` status and its lease has not expired (`updatedAt + leaseTtl > now`), the intervention is rejected with HTTP 409 Conflict.
2. **Mandatory Justification**: The operator must provide a non-empty `justification` string and `operatorId`.
3. **Monotonic Fencing Token**: Step retries generate an incremented fencing token, preventing any delayed zombie worker from submitting responses.
4. **Immutable Audit Event**: The action atomically logs an `OPERATOR_INTERVENTION` audit event in PostgreSQL.

### Rationale
- Fulfills Constitution Principle I (Zero Double-Spend) and Principle II (Strict Backward Compensation & Zombie-Proofing).
- Prevents split-brain execution where an operator overrides a step while a worker thread is still actively updating balances.

### Alternatives Considered
- **Unconditional Force Override**: High risk of double-spend if an external gateway call was merely slow rather than dead.
- **Direct Database Flag Updates**: Bypasses audit trail and does not notify other nodes.

---

## Decision 5: Idempotency Forensic Query Engine

### Decision
Expose a dedicated forensic endpoint `GET /api/v1/dashboard/idempotency` supporting query by `key`, `payloadHash`, `tenantId`, and `status`. If a search matches an idempotency key, compute the SHA-256 digest of stored request payloads and compare against historical entries to visually flag tampering attempts.

### Rationale
- Supports customer support and compliance investigation workflows with instant sub-second lookup.
- Provides immediate visual forensic evidence when client applications send conflicting payloads with reused keys.

### Alternatives Considered
- **Raw SQL Query Tool in Dashboard**: Security hazard (SQL injection risks and arbitrary table access).
