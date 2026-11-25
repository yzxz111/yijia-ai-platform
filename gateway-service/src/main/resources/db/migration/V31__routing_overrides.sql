CREATE TABLE gateway.routing_override (
    routing_model_alias varchar(128) PRIMARY KEY,
    channel_id uuid NOT NULL REFERENCES gateway.channel(id) ON DELETE RESTRICT,
    actor_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_routing_override_model_alias CHECK (length(btrim(routing_model_alias)) > 0)
);

CREATE INDEX ix_routing_override_channel
    ON gateway.routing_override (channel_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gateway_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON gateway.routing_override TO gateway_app;
    END IF;
END
$$;
