DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gateway_app') THEN
        GRANT USAGE ON SCHEMA platform TO gateway_app;
        GRANT SELECT ON platform.app_user TO gateway_app;
    END IF;
END
$$;
