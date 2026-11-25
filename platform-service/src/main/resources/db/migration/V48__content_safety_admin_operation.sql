CREATE TABLE platform.content_safety_admin_operation (
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL REFERENCES platform.app_user(id),
    action varchar(128) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_sha256 char(64) NOT NULL,
    resource_type varchar(64),
    resource_id varchar(128),
    response_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT uq_content_safety_admin_operation
        UNIQUE (actor_user_id, action, idempotency_key),
    CONSTRAINT ck_content_safety_admin_operation_hash
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_content_safety_admin_operation_completion
        CHECK (
            (response_json IS NULL AND completed_at IS NULL AND resource_type IS NULL)
            OR
            (response_json IS NOT NULL AND completed_at IS NOT NULL AND resource_type IS NOT NULL)
        )
);

CREATE INDEX ix_content_safety_admin_operation_created
    ON platform.content_safety_admin_operation (created_at DESC);
