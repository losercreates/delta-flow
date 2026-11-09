CREATE TABLE idempotency_records (
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    lock_token VARCHAR(64),
    response_status_code INT,
    response_headers JSONB,
    response_payload BYTEA,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_lease_expiry 
    ON idempotency_records (status, lease_expires_at);

CREATE INDEX idx_idempotency_created_at 
    ON idempotency_records (created_at);

