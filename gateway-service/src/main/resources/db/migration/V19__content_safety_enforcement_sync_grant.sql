DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_app') THEN
        GRANT SELECT, INSERT, UPDATE ON gateway.safety_enforcement_cache TO platform_app;
    END IF;
END
$$;
