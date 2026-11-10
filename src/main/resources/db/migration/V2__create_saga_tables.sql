CREATE TABLE saga_instances (
    saga_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    idempotency_key VARCHAR(128) NOT NULL,
    saga_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step INT NOT NULL DEFAULT 0,
    payload JSONB NOT NULL,
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_saga_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_saga_instances_status 
    ON saga_instances (status);

CREATE INDEX idx_saga_instances_tenant_key 
    ON saga_instances (tenant_id, idempotency_key);

CREATE TABLE saga_steps (
    step_id UUID PRIMARY KEY,
    saga_id UUID NOT NULL REFERENCES saga_instances(saga_id) ON DELETE CASCADE,
    step_name VARCHAR(64) NOT NULL,
    step_index INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_payload JSONB,
    response_payload JSONB,
    error_details TEXT,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_saga_step_index UNIQUE (saga_id, step_index)
);

CREATE INDEX idx_saga_steps_saga_status 
    ON saga_steps (saga_id, status);

CREATE INDEX idx_saga_steps_lease 
    ON saga_steps (status, lease_expires_at);

