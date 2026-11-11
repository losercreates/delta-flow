ALTER TABLE saga_steps 
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 1;

CREATE INDEX idx_saga_steps_fencing 
    ON saga_steps (saga_id, step_index, fencing_token);

CREATE INDEX idx_saga_instances_recovery 
    ON saga_instances (status, updated_at);

