-- Flyway Migration: V7__tenant_api_keys.sql
-- Table for storing pre-shared machine client API keys with cryptographic hash digests

CREATE TABLE IF NOT EXISTS tenant_api_keys (
    key_id VARCHAR(64) PRIMARY KEY,
    hashed_key VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    description VARCHAR(256),
    roles VARCHAR(256) NOT NULL DEFAULT 'ROLE_SERVICE',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Partial index for fast constant-time lookup of enabled keys
CREATE INDEX IF NOT EXISTS idx_tenant_api_keys_hash 
    ON tenant_api_keys (hashed_key) 
    WHERE enabled = TRUE;

-- Pre-seeded test machine API key: df_live_tenant_alpha_secret_key_12345
-- SHA-256: 6dc280d121f7f4b99a2c75445848f87962f2e5d1c0fd1ce06bf88f6e7eb15830
INSERT INTO tenant_api_keys (key_id, hashed_key, tenant_id, description, roles, enabled)
VALUES (
    'key-alpha-service',
    '6dc280d121f7f4b99a2c75445848f87962f2e5d1c0fd1ce06bf88f6e7eb15830',
    'tenant-alpha',
    'Tenant Alpha Machine Service Account',
    'ROLE_SERVICE',
    TRUE
) ON CONFLICT (key_id) DO NOTHING;
