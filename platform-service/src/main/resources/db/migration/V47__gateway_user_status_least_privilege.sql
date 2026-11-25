CREATE OR REPLACE VIEW platform.gateway_user_status
WITH (security_barrier = true)
AS
SELECT tenant_id, id, status
FROM platform.app_user;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gateway_app') THEN
        REVOKE SELECT ON platform.app_user FROM gateway_app;
        GRANT USAGE ON SCHEMA platform TO gateway_app;
        GRANT SELECT ON platform.gateway_user_status TO gateway_app;
    END IF;
END
$$;
