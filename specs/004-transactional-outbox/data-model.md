# Data Model: Transactional Outbox & Resilience Policies

DeltaFlow extends the relational schema with a dedicated transactional outbox table and defines configuration models for circuit breakers and parallel branches.

---

## 1. Database Schema (`outbox_events`)

```sql
-- Flyway Migration: V4__create_outbox_events_table.sql

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    error_details TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

-- Index for background publisher scanning pending records in order
CREATE INDEX idx_outbox_events_status_created 
    ON outbox_events (status, created_at ASC);

-- Index for forensic lookup by saga or entity ID
CREATE INDEX idx_outbox_events_aggregate 
    ON outbox_events (aggregate_type, aggregate_id);
```

---

## 2. Entity Model: `OutboxEvent`

| Field | Type | Constraints | Description |
|---|---|---|---|
| `event_id` | `UUID` | Primary Key | Globally unique event identifier |
| `aggregate_type` | `VARCHAR(64)` | NOT NULL | Type of aggregate (e.g. `SAGA_INSTANCE`, `TRANSFER`) |
| `aggregate_id` | `VARCHAR(128)` | NOT NULL | ID of the aggregate (e.g. sagaId or transferKey) |
| `event_type` | `VARCHAR(64)` | NOT NULL | Specific event name (e.g. `TRANSFER_COMPLETED`, `TRANSFER_COMPENSATED`) |
| `payload` | `JSONB` | NOT NULL | Full domain event payload in JSON format |
| `status` | `VARCHAR(32)` | NOT NULL | Current state: `PENDING`, `IN_FLIGHT`, `PUBLISHED`, `DEAD_LETTER` |
| `retry_count` | `INT` | NOT NULL, DEFAULT 0 | Count of failed transmission attempts |
| `error_details` | `TEXT` | Nullable | Stack trace or failure reason if dispatch failed |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | Timestamp when the event was atomically persisted |
| `published_at` | `TIMESTAMPTZ` | Nullable | Timestamp when destination confirmed delivery |

### State Transitions

```
[ PENDING ] ──(claim by poller)──> [ IN_FLIGHT ] ──(dispatch success)──> [ PUBLISHED ]
                                         │
                                   (error & retries < max)
                                         │
                                         ▼
                                   [ PENDING ] (retry_count++)
                                         │
                                   (retries >= max)
                                         ▼
                                  [ DEAD_LETTER ]
```

---

## 3. Parallel Branch Model: `ParallelBranchDefinition`

Represented as an immutable record within a parallel saga step:

```java
public record ParallelBranchDefinition<I>(
    String branchName,
    BiFunction<SagaExecutionContext, I, ?> action,
    BiConsumer<SagaExecutionContext, I> compensation
) {}
```

- Each parallel step holds a `List<ParallelBranchDefinition<I>>`.
- Executed concurrently within a `StructuredTaskScope.ShutdownOnFailure` scope using virtual threads.
- If any branch fails, all running branches are cancelled and any completed branches are compensated.

---

## 4. Resilience Policy Model

Defines the parameters for circuit breaking and retries attached to external steps:

```java
public record ResiliencePolicy(
    String policyName,
    int slidingWindowSize,        // Default: 10 calls
    float failureRateThreshold,   // Default: 50.0%
    Duration waitDurationInOpen,  // Default: 5s
    int halfOpenPermittedCalls,   // Default: 3
    int maxRetryAttempts,         // Default: 3
    Duration initialRetryInterval,// Default: 100ms
    double backoffMultiplier      // Default: 2.0
) {}
```

