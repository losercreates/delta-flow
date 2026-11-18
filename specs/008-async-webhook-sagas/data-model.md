# Data Model: Event-Driven Messaging & Asynchronous Webhook Sagas

**Feature Branch**: `008-async-webhook-sagas`  
**Date**: 2026-11-18  
**Spec Reference**: [spec.md](./spec.md) | [research.md](./research.md)

---

## 1. Relational Schema Mutations (PostgreSQL 16)

### 1.1 `saga_instances` Table Schema Updates

Add columns to track asynchronous event waiting and step expiration deadlines:

```sql
-- Migration: V8__add_async_saga_suspension_support.sql

ALTER TABLE saga_instances 
    ADD COLUMN IF NOT EXISTS awaiting_event_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS step_deadline TIMESTAMP WITH TIME ZONE;

-- Index for the SagaRecoveryScanner to quickly find expired suspended sagas
CREATE INDEX IF NOT EXISTS idx_saga_instances_suspended_deadline 
    ON saga_instances (status, step_deadline) 
    WHERE status = 'SUSPENDED';
```

**Status Enum Values**:
- `PENDING`
- `EXECUTING`
- `SUSPENDED` *(new in Milestone 8)*
- `TIMED_OUT` *(new in Milestone 8)*
- `COMPENSATING`
- `COMPENSATED`
- `COMPLETED`
- `FAILED`

---

### 1.2 `saga_steps` Table Schema Updates

```sql
ALTER TABLE saga_steps 
    ADD COLUMN IF NOT EXISTS awaiting_event_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS step_deadline TIMESTAMP WITH TIME ZONE;
```

**Step Status Enum Values**:
- `NOT_STARTED`
- `EXECUTING`
- `SUSPENDED` *(new in Milestone 8)*
- `COMPLETED`
- `FAILED`
- `COMPENSATING`
- `COMPENSATED`

---

### 1.3 `outbox_events` Table Schema Updates

Enhance outbox records with partition key and dead-letter queue classification:

```sql
ALTER TABLE outbox_events 
    ADD COLUMN IF NOT EXISTS partition_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_details TEXT;

-- Update check constraint or enum for status to support DEAD_LETTER
-- Status values: 'PENDING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER'
CREATE INDEX IF NOT EXISTS idx_outbox_events_status_pending 
    ON outbox_events (status, created_at) 
    WHERE status = 'PENDING';
```

---

### 1.4 `webhook_ingress_records` (New Table)

Records all incoming external webhook submissions for auditability, non-repudiation, and replay prevention:

```sql
CREATE TABLE IF NOT EXISTS webhook_ingress_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    event_name VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL, -- 'ACCEPTED', 'REJECTED_SIGNATURE', 'REJECTED_TIMESTAMP', 'REJECTED_ZOMBIE', 'REPLAYED'
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_webhook_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_webhook_records_saga 
    ON webhook_ingress_records (saga_id, event_name);
```

---

## 2. Java 21 Domain Entities & Sealed Hierarchies

### 2.1 Extended `SagaState` Sealed Interface

```java
package io.deltaflow.saga.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public sealed interface SagaState permits 
    SagaState.Pending, 
    SagaState.Executing, 
    SagaState.Suspended,     // NEW
    SagaState.TimedOut,      // NEW
    SagaState.Completed, 
    SagaState.Failed, 
    SagaState.Compensating,
    SagaState.Compensated {

    // Existing records: Pending, Executing, Completed, Failed, Compensating, Compensated...

    record Suspended(
        UUID sagaId,
        String tenantId,
        int stepIndex,
        String stepName,
        String awaitingEventName,
        Instant stepDeadline,
        Instant suspendedAt
    ) implements SagaState {}

    record TimedOut(
        UUID sagaId,
        String tenantId,
        int stepIndex,
        String stepName,
        Instant timedOutAt
    ) implements SagaState {}
}
```

---

### 2.2 Extended `SagaStepState` Sealed Interface

```java
package io.deltaflow.saga.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public sealed interface SagaStepState permits 
    SagaStepState.NotStarted, 
    SagaStepState.Executing, 
    SagaStepState.Suspended,   // NEW
    SagaStepState.Completed, 
    SagaStepState.Failed, 
    SagaStepState.Compensating,
    SagaStepState.Compensated {

    record NotStarted(UUID stepId, int stepIndex, String stepName) implements SagaStepState {}
    record Executing(UUID stepId, int stepIndex, String stepName, Map<String, Object> requestPayload, Instant startedAt) implements SagaStepState {}
    record Suspended(UUID stepId, int stepIndex, String stepName, String awaitingEventName, Instant stepDeadline, Instant suspendedAt) implements SagaStepState {}
    record Completed(UUID stepId, int stepIndex, String stepName, Map<String, Object> responsePayload, Instant completedAt) implements SagaStepState {}
    record Failed(UUID stepId, int stepIndex, String stepName, String errorDetails, Instant failedAt) implements SagaStepState {}
    record Compensating(UUID stepId, int stepIndex, String stepName, Instant startedAt) implements SagaStepState {}
    record Compensated(UUID stepId, int stepIndex, String stepName, Instant compensatedAt) implements SagaStepState {}
}
```

---

### 2.3 Kafka Outbox Broker Envelope Record

```java
package io.deltaflow.outbox.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KafkaEventEnvelope(
    UUID eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    String tenantId,
    String payload,
    String traceparent,
    Instant timestamp,
    Map<String, String> headers
) {}
```

---

### 2.4 Webhook Callback DTOs

```java
package io.deltaflow.saga.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record WebhookCallbackRequest(
    @NotBlank String eventName,
    String status, // e.g. "APPROVED", "REJECTED", "SETTLED"
    Map<String, Object> data
) {}

public record WebhookCallbackResponse(
    UUID sagaId,
    String eventName,
    String status, // "RESUMED", "COMPLETED", "REPLAYED"
    Instant processedAt
) {}
```
