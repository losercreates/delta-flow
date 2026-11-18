ALTER TABLE saga_instances 
    ADD COLUMN IF NOT EXISTS awaiting_event_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS step_deadline TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_saga_instances_suspended_deadline 
    ON saga_instances (status, step_deadline) 
    WHERE status = 'SUSPENDED';

ALTER TABLE saga_steps 
    ADD COLUMN IF NOT EXISTS awaiting_event_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS step_deadline TIMESTAMPTZ;

ALTER TABLE outbox_events 
    ADD COLUMN IF NOT EXISTS partition_key VARCHAR(128);

CREATE TABLE IF NOT EXISTS webhook_ingress_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    event_name VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    signature_valid BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_webhook_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_webhook_records_saga 
    ON webhook_ingress_records (saga_id, event_name);
