DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'platform_app') THEN
        CREATE ROLE platform_app LOGIN PASSWORD 'local-platform-password';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gateway_app') THEN
        CREATE ROLE gateway_app LOGIN PASSWORD 'local-gateway-password';
    END IF;
END
$$;

ALTER ROLE platform_app SET search_path = platform;
ALTER ROLE gateway_app SET search_path = gateway;

CREATE SCHEMA IF NOT EXISTS platform AUTHORIZATION platform_app;
CREATE SCHEMA IF NOT EXISTS gateway AUTHORIZATION gateway_app;

REVOKE ALL ON SCHEMA platform FROM PUBLIC;
REVOKE ALL ON SCHEMA gateway FROM PUBLIC;
REVOKE ALL ON SCHEMA platform FROM gateway_app;
REVOKE ALL ON SCHEMA gateway FROM platform_app;

GRANT CONNECT ON DATABASE api_model_platform TO platform_app, gateway_app;
GRANT USAGE, CREATE ON SCHEMA platform TO platform_app;
GRANT USAGE, CREATE ON SCHEMA gateway TO gateway_app;
GRANT USAGE ON SCHEMA gateway TO platform_app;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
