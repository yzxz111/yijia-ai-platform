CREATE TABLE gateway.managed_api_key_rotation (
    operation_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    actor_user_id uuid NOT NULL,
    target_user_id uuid NOT NULL,
    old_api_key_id uuid NOT NULL,
    new_api_key_id uuid,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_managed_api_key_rotation_status
        CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT fk_managed_api_key_rotation_old_owner
        FOREIGN KEY (old_api_key_id, tenant_id, target_user_id)
        REFERENCES gateway.api_key (id, tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_managed_api_key_rotation_new_owner
        FOREIGN KEY (new_api_key_id, tenant_id, target_user_id)
        REFERENCES gateway.api_key (id, tenant_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_managed_api_key_rotation_completion CHECK (
        (status = 'PENDING' AND new_api_key_id IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND new_api_key_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_managed_api_key_rotation_target
    ON gateway.managed_api_key_rotation (tenant_id, old_api_key_id, created_at DESC);
