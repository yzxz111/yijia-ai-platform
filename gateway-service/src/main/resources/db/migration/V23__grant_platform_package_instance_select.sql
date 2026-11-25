DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_app') THEN
        GRANT SELECT ON gateway.package_instance TO platform_app;
    END IF;
END;
$$;
