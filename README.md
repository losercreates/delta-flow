# DeltaFlow

> **Distributed Idempotency & Saga Orchestrator for Financial APIs**

[![Java](https://img.shields.io/badge/Java-21%20LTS-ED8B00?style=flat&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=flat&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?style=flat&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=flat&logo=redis&logoColor=white)](https://redis.io/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

DeltaFlow is a high-consistency coordination proxy and transactional saga orchestrator engineered to guarantee **exactly-once execution** and **durable forward progression** for distributed payment processing and financial transfers.

---

## The Problem

Distributed financial systems and microservice architectures frequently encounter partial failures:
- **Duplicate Debits**: A payment request times out over the network while the debit succeeds remotely. An impatient client or automated gateway retries, double-charging the customer.
- **Payload Tampering / Key Collision**: A client reuses an `Idempotency-Key` but alters the payload (e.g. changing transaction amount or recipient account).
- **In-Flight Contention**: Rapid concurrent requests with the identical idempotency key arrive within milliseconds, creating race conditions across worker nodes.
- **Unrecorded Step Intent**: A service crashes while dispatching a payment step. Without a Write-Ahead Log (WAL), the system cannot determine if the outbound call was initiated.
- **Stranded States & Zombie Steps**: Worker processes crash mid-transaction, leaving distributed locks permanently orphaned.

DeltaFlow combines a high-speed distributed idempotency gate with a relational Write-Ahead Log (WAL) saga engine, providing deterministic multi-step transaction coordination with zero proprietary DSLs.

---

## Core Architecture

```mermaid
flowchart TD
    Client["Client / API Gateway"] -->|"POST /api/v1/transfers<br/>[Idempotency-Key, Payload]"| Filter["IdempotencyFilter"]
    
    subgraph Gate ["1. Distributed Idempotency Gate"]
        Filter --> Hasher["PayloadHasher<br/>(SHA-256 Digest)"]
        Hasher --> GateCoord["IdempotencyCoordinator"]
        
        GateCoord <-->|"Primary Distributed Lock"| Redis[("Redis 7<br/>Redisson RLock")]
        GateCoord <-->|"Pessimistic Fallback"| PG_Lock[("PostgreSQL 16<br/>PESSIMISTIC_WRITE")]
    end
    
    GateCoord -->|"Cache Hit (RESOLVED)"| ReturnCached["Return Cached Response<br/>[Idempotency-Status: CACHED]"]
    ReturnCached --> Client
    
    GateCoord -->|"Lock Acquired (PROCESSING)"| SagaEng["2. Saga Coordinator Engine"]
    
    subgraph SagaEngine ["Declarative Saga Coordinator & WAL Engine"]
        SagaEng --> WAL_Init["WAL: Record Saga Pending/Executing"]
        WAL_Init --> WAL_Store[("PostgreSQL 16<br/>saga_instances & saga_steps")]
        
        WAL_Init --> Step1["Step 1: Debit Account"]
        Step1 -->|"WAL Intent (REQUIRES_NEW)"| WAL_Store
        Step1 -->|"Outbound Call"| DebitSvc["Debit Service"]
        DebitSvc -->|"WAL Step Completed"| WAL_Store
        
        Step1 --> Step2["Step 2: Credit Account"]
        Step2 -->|"WAL Intent (REQUIRES_NEW)"| WAL_Store
        Step2 -->|"Outbound Call"| CreditSvc["Credit Service"]
        CreditSvc -->|"WAL Step Completed"| WAL_Store
        
        Step2 --> Step3["Step 3: Post Ledger Entry"]
        Step3 -->|"WAL Intent (REQUIRES_NEW)"| WAL_Store
        Step3 -->|"Outbound Call"| LedgerSvc["Ledger Service"]
        LedgerSvc -->|"WAL Step Completed"| WAL_Store
        
        Step3 --> Finalize["WAL: Complete Saga"]
        Finalize --> WAL_Store
    end
    
    Finalize -->|"Save Resolved Result"| GateCoord
    GateCoord --> ReturnDirect["Return Response<br/>[Idempotency-Status: DIRECT]"]
    ReturnDirect --> Client
```

---

## Key Capabilities

1. **Relational Write-Ahead Log (WAL) Journaling**:
   - Every step's intent and request payload are durably committed to PostgreSQL (`Propagation.REQUIRES_NEW`) *prior* to outbound remote execution.
   - Preserves complete step execution records in `saga_steps` (request/response JSONB, timestamps, lease deadlines, failure details).

2. **Declarative Fluent Saga API**:
   - Define multi-step pipelines cleanly in type-safe Java code:
   ```java
   SagaDefinition<TransferRequestDto, TransferResponseDto> saga = SagaBuilder
           .<TransferRequestDto, TransferResponseDto>named("account-transfer")
           .step("debit-step")
               .action((ctx, req) -> debitService.debit(req.sourceAccountId(), req.amount(), ctx.getIdempotencyKey()))
               .compensate((ctx, req) -> debitService.compensateDebit(req.sourceAccountId(), req.amount(), ctx.getIdempotencyKey()))
           .step("credit-step")
               .action((ctx, req) -> creditService.credit(req.targetAccountId(), req.amount(), ctx.getIdempotencyKey()))
               .compensate((ctx, req) -> creditService.compensateCredit(req.targetAccountId(), req.amount(), ctx.getIdempotencyKey()))
           .step("ledger-step")
               .action((ctx, req) -> ledgerService.recordEntry(
                       ctx.getStepResult("debit-step", DebitResult.class).debitId(),
                       ctx.getStepResult("credit-step", CreditResult.class).creditId(),
                       req.amount(),
                       req.currency()
               ))
           .onCompletion((ctx, req) -> new TransferResponseDto(ctx.getSagaId(), "tx_" + ctx.getIdempotencyKey(), "COMPLETED", req.amount(), req.currency(), 3, Instant.now()))
           .build();
   ```

3. **Java 21 Sealed Lifecycle State Machines**:
   - Saga instances and individual steps transition through sealed hierarchies (`Pending`, `Executing`, `Completed`, `Failed`, `Compensating`).
   - Evaluated using compile-time exhaustive switch pattern matching with zero `default` catch-alls.

4. **Cryptographic Payload Tampering Protection**:
   - Incoming request payloads are hashed using SHA-256 digests.
   - Any reuse of an existing idempotency key with an altered payload is immediately rejected with `HTTP 409 Conflict (IDEMPOTENCY_KEY_PAYLOAD_MISMATCH)`.

5. **Tiered Distributed Locking**:
   - **Primary**: Redisson distributed locking on Redis 7 with sub-millisecond lease management.
   - **Failover**: Automatic fallback to PostgreSQL row-level pessimistic locking (`PESSIMISTIC_WRITE`).

6. **Immutable Context Flow**:
   - Downstream steps consume verified outputs from upstream steps through an immutable `SagaExecutionContext`.

7. **Structured Audit Trail**:
   - Emits immutable JSON audit log events (`SAGA_STARTED`, `STEP_EXECUTING`, `STEP_COMPLETED`, `SAGA_COMPLETED`, `STEP_FAILED`) for regulatory compliance (SOC2/PCI-DSS).

---

## Tech Stack

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Language** | Java 21 LTS | Sealed Interfaces, Pattern Matching, Record Patterns, Virtual Threads |
| **Framework** | Spring Boot 3.3.5 | Spring Web, Spring Data JPA, Hibernate 6, Actuator, Micrometer Prometheus |
| **Relational Storage** | PostgreSQL 16 | ACID persistence, `JSONB` journals, `PESSIMISTIC_WRITE` row locks, Flyway migrations |
| **Distributed Cache** | Redis 7 & Redisson | High-throughput distributed locks and TTL lease management |
| **Distributed Tracing** | OpenTelemetry & Jaeger | W3C `traceparent` context propagation, OTLP span export, Jaeger UI |
| **Metrics & Dashboards** | Prometheus & Grafana | Micrometer Prometheus scraping (/actuator/prometheus), pre-provisioned Grafana dashboard |
| **Operator Dashboard** | React 18, Vite & Tailwind | Modern SPA, @xyflow/react interactive DAG graph, Nginx reverse proxy |
| **Testing** | Testcontainers 1.20 | Containerized PostgreSQL 16, Redis 7, and Toxiproxy chaos tests |

---

## Production Packaging & Multi-Container Stack

DeltaFlow includes a turnkey **Docker Compose v2** multi-container deployment orchestrating all seven services with declarative health checks, persistent volumes, and auto-provisioned dashboards.

### Port Mappings & Services

| Service | Container Name | Port | Description |
| :--- | :--- | :--- | :--- |
| **Operator Dashboard** | `deltaflow-frontend` | `3000` | React / Vite UI with live DAG graph (Nginx reverse proxy) |
| **DeltaFlow Backend** | `deltaflow-backend` | `8080` | Spring Boot 3.3.5 / Java 21 runtime (`--enable-preview`) |
| **Grafana** | `deltaflow-grafana` | `3001` | Pre-provisioned dashboards & PromQL visualizations |
| **Jaeger UI** | `deltaflow-jaeger` | `16686` | Distributed W3C trace waterfall search and visualization |
| **Prometheus** | `deltaflow-prometheus` | `9090` | Time-series metrics scraper (5s interval) |
| **PostgreSQL 16** | `deltaflow-postgres` | `5432` | Relational WAL journal and ACID persistence |
| **Redis 7** | `deltaflow-redis` | `6379` | High-performance distributed locks and lease manager |

---

## Getting Started

### Prerequisites
- **Java 21** or higher
- **Maven 3.9+**
- **Docker & Docker Compose** (for multi-container deployment & Testcontainers)

---

### Option A: One-Command Docker Compose Stack (Recommended)

Start the entire 7-service ecosystem in the background:

```bash
# 1. Bring up all services with health checks and volume persistence
docker compose up -d
# (or simply: make up)

# 2. Verify all containers report healthy status
docker compose ps
# (or: make ps)

# 3. Run automated health & financial smoke test verification
make smoke
```

When finished:
```bash
# Gracefully stop containers
docker compose down
# (or stop and purge volumes: make clean)
```

---

### Option B: Local Development Workflow

#### 1. Launch Infrastructure
Start PostgreSQL and Redis:

```bash
docker run -d --name deltaflow-postgres -e POSTGRES_DB=deltaflow -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16-alpine
docker run -d --name deltaflow-redis -p 6379:6379 redis:7-alpine
```

#### 2. Build and Run Backend
```bash
mvn clean spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview"
```

The service will start on `http://localhost:8080`.
- **OpenAPI Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **Prometheus Metrics**: `http://localhost:8080/actuator/prometheus`
- **Readiness Health Probe**: `http://localhost:8080/actuator/health/readiness`

---

## API Usage & Verification Scenarios

### Scenario 1: Execute 3-Step Account Transfer
Submit an account transfer with a client-generated `Idempotency-Key`:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: xfer_demo_1001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 500.00,
    "currency": "USD"
  }'
```

**Response (`201 Created`, Direct Execution)**:
```http
HTTP/1.1 201 Created
Idempotency-Status: DIRECT
Content-Type: application/json

{
  "sagaId": "4a73e86f-5b6d-4ee8-809e-0129a089b031",
  "transactionId": "tx_xfer_demo_1001",
  "status": "COMPLETED",
  "amount": 500.00,
  "currency": "USD",
  "stepsCompleted": 3,
  "executedAt": "2026-11-10T06:45:00Z"
}
```

---

### Scenario 2: Identical Replay (Zero Downstream Execution)
Resend the identical request with the same `Idempotency-Key`:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: xfer_demo_1001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 500.00,
    "currency": "USD"
  }'
```

**Response (`201 Created`, Served from Cache)**:
```http
HTTP/1.1 201 Created
Idempotency-Status: CACHED
Content-Type: application/json

{
  "sagaId": "4a73e86f-5b6d-4ee8-809e-0129a089b031",
  "transactionId": "tx_xfer_demo_1001",
  "status": "COMPLETED",
  "amount": 500.00,
  "currency": "USD",
  "stepsCompleted": 3,
  "executedAt": "2026-11-10T06:45:00Z"
}
```
*Returned in <10ms directly from cache with zero participant step executions.*

---

### Scenario 3: Payload Tampering Rejection
Submit a request using the same `Idempotency-Key` but an altered amount:

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: xfer_demo_1001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 9999.00,
    "currency": "USD"
  }'
```

**Response (`409 Conflict`)**:
```http
HTTP/1.1 409 Conflict
Content-Type: application/json

{
  "errorCode": "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
  "message": "Payload SHA-256 digest does not match the original request for this idempotency key.",
  "idempotencyKey": "xfer_demo_1001"
}
```

---

### Scenario 4: Inspect WAL Journal Records
Inspect top-level saga status and detailed step journals:

```bash
# Get Saga Instance Details
curl -s http://localhost:8080/api/v1/sagas/4a73e86f-5b6d-4ee8-809e-0129a089b031

# Get Write-Ahead Log Step Records
curl -s http://localhost:8080/api/v1/sagas/4a73e86f-5b6d-4ee8-809e-0129a089b031/steps
```

---

### Scenario 5: Asynchronous Suspended Sagas & Cryptographic Webhook Ingress

When a saga encounters a step requiring external callback (e.g. 3DS OTP verification or manual fraud review), it suspends, releases virtual threads and distributed lock leases, and enters `SUSPENDED` status.

Deliver an authenticated webhook callback to resume forward execution:

```bash
# Compute timestamp and HMAC-SHA256 signature
TIMESTAMP=$(date +%s%3N)
BODY='{"status":"APPROVED","providerReference":"ch_3N8cXYZ","data":{"riskScore":12}}'
SECRET="deltaflow-webhook-secret-key-default"
SIGNATURE=$(echo -n "${TIMESTAMP}.${BODY}" | openssl dgst -sha256 -hmac "${SECRET}" | sed 's/^.* //')

# Deliver signed webhook callback
curl -i -X POST http://localhost:8080/api/v1/sagas/4a73e86f-5b6d-4ee8-809e-0129a089b031/events/3ds-verification \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Timestamp: ${TIMESTAMP}" \
  -H "X-Webhook-Signature: ${SIGNATURE}" \
  -H "Idempotency-Key: cb_3ds_4a73e86f" \
  -d "${BODY}"
```

**Response (`200 OK`)**:
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "sagaId": "4a73e86f-5b6d-4ee8-809e-0129a089b031",
  "eventName": "3ds-verification",
  "status": "RESUMED",
  "processedAt": "2026-11-18T14:45:00Z"
}
```

*If the callback deadline expires before webhook delivery, DeltaFlow's recovery scanner detects the timeout, transitions the saga to `TIMED_OUT`, and initiates automatic backward compensation in reverse LIFO order.*

---

## Event-Driven Outbox Dispatching (Kafka / Redpanda)

DeltaFlow includes pluggable domain event streaming to Kafka topics:
- **Partitioned Topic Dispatch**: All saga events are deterministically partitioned by `sagaId` onto `deltaflow.domain-events`.
- **W3C Distributed Tracing**: OpenTelemetry `traceparent` headers are injected directly into Kafka record headers.
- **Dead-Letter Queue (DLQ)**: Poisoned events exceeding delivery retry budgets are automatically routed to `deltaflow.domain-events.dlq`.

---

## Testing

Run the test suite (requires Docker for Testcontainers):

```bash
mvn test
```

---

## License

Distributed under the Apache 2.0 License. See `LICENSE` for details.
