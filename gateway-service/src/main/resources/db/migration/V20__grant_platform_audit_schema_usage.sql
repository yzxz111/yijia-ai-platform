DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_app') THEN
        GRANT USAGE ON SCHEMA gateway TO platform_app;
        GRANT SELECT ON gateway.admin_operation_audit TO platform_app;
    END IF;
END
$$;
