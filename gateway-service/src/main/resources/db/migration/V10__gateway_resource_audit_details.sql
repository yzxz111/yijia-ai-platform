ALTER TABLE gateway.admin_operation_audit
    ALTER COLUMN idempotency_key DROP NOT NULL,
    ALTER COLUMN tenant_id DROP NOT NULL,
    ALTER COLUMN request_id DROP NOT NULL,
    ADD COLUMN resource_type varchar(64) NOT NULL DEFAULT 'GATEWAY_OPERATION',
    ADD COLUMN resource_id varchar(128),
    ADD COLUMN detail jsonb NOT NULL DEFAULT '{}'::jsonb;
