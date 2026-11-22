-- 1. Extend outbox_events for lease expiration and worker claiming
ALTER TABLE outbox_events 
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS claimed_by_worker VARCHAR(128);

-- 2. Partial index for sub-millisecond in-flight lease recovery scans
CREATE INDEX IF NOT EXISTS idx_outbox_events_inflight_lease 
    ON outbox_events (status, lease_expires_at) 
    WHERE status = 'IN_FLIGHT';

-- 3. Partial index for high-performance idempotency retention pruning
CREATE INDEX IF NOT EXISTS idx_idempotency_retention 
    ON idempotency_records (status, created_at) 
    WHERE status = 'RESOLVED';
