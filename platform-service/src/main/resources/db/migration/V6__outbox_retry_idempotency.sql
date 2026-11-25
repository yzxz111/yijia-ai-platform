CREATE TABLE platform.outbox_retry_request (
    business_no varchar(128) PRIMARY KEY,
    event_id uuid NOT NULL REFERENCES platform.outbox_event(id) ON DELETE CASCADE,
    actor_user_id uuid REFERENCES platform.app_user(id) ON DELETE SET NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_outbox_retry_event
    ON platform.outbox_retry_request (event_id, created_at DESC);
