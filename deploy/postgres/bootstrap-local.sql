\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

INSERT INTO platform.app_user(
    id, tenant_id, email, password_hash, display_name, status, global_admin)
VALUES (
    '70000000-0000-4000-8000-000000000001',
    '70000000-0000-4000-8000-000000000001',
    lower(:'admin_email'), crypt(:'admin_password', gen_salt('bf', 12)),
    '本地管理员', 'ACTIVE', true)
ON CONFLICT DO NOTHING;

-- Identity and privileges are converged on every run, but the password is only seeded when the
-- account does not have one yet. This script executes on every deploy, so an unconditional
-- UPDATE would reset an operator-rotated administrator password back to the value in the
-- deployment environment each time the stack came up.
UPDATE platform.app_user
SET email = lower(:'admin_email'),
    password_hash = CASE
        WHEN password_hash IS NULL OR password_hash = ''
            THEN crypt(:'admin_password', gen_salt('bf', 12))
        ELSE password_hash
    END,
    display_name = '本地管理员',
    status = 'ACTIVE',
    global_admin = true,
    updated_at = now()
WHERE id = '70000000-0000-4000-8000-000000000001';

INSERT INTO platform.user_role(tenant_id, user_id, role_id)
SELECT app_user.tenant_id, app_user.id, app_role.id
FROM platform.app_user app_user
JOIN platform.app_role app_role ON app_role.code = 'ADMIN'
WHERE lower(app_user.email) = lower(:'admin_email')
ON CONFLICT (user_id, role_id) DO NOTHING;

UPDATE platform.payment_config
SET enabled = true
WHERE provider = 'MOCK' AND environment = 'LOCAL';

BEGIN;

INSERT INTO gateway.provider(id, code, name, provider_type, base_url, enabled)
VALUES (
    '87000000-0000-4000-8000-000000000001',
    'local-mock', '本地模拟服务', 'MOCK', 'http://wiremock:8080', true)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    provider_type = EXCLUDED.provider_type,
    base_url = EXCLUDED.base_url,
    enabled = true,
    updated_at = now();

INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, encrypted_api_key,
    enabled, priority, weight, timeout_ms, requests_per_minute,
    max_concurrency, last_health_status)
VALUES
    ('87000000-0000-4000-8000-000000000101',
     (SELECT id FROM gateway.provider WHERE code = 'local-mock'),
     'local-mock-primary', '本地模拟主渠道', 'LOCAL',
     'http://wiremock:8080/mock-primary', NULL,
     true, 200, 100, 10000, 1000, 100, 'HEALTHY'),
    ('87000000-0000-4000-8000-000000000102',
     (SELECT id FROM gateway.provider WHERE code = 'local-mock'),
     'local-mock-failover', '本地模拟故障转移渠道', 'LOCAL',
     'http://wiremock:8080/mock-failover', NULL,
     true, 100, 100, 10000, 1000, 100, 'HEALTHY')
ON CONFLICT (code) DO UPDATE SET
    provider_id = EXCLUDED.provider_id,
    name = EXCLUDED.name,
    environment = EXCLUDED.environment,
    base_url = EXCLUDED.base_url,
    encrypted_api_key = NULL,
    enabled = true,
    priority = EXCLUDED.priority,
    weight = EXCLUDED.weight,
    timeout_ms = EXCLUDED.timeout_ms,
    requests_per_minute = EXCLUDED.requests_per_minute,
    max_concurrency = EXCLUDED.max_concurrency,
    last_health_status = 'HEALTHY',
    updated_at = now();

INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT gen_random_uuid(), channel.id, model.id, model.code, true, model.capabilities
FROM gateway.channel channel
CROSS JOIN gateway.model model
WHERE channel.code IN ('local-mock-primary', 'local-mock-failover')
  AND model.code IN (
      'text-basic', 'text-standard', 'vision-basic', 'file-basic',
      'text-pro', 'vision-pro', 'text-max',
      -- 客户端目录里唯一可见的文本入口（V102/V103），本地联调必须能真的调到它们，
      -- 否则桌面端在本地会"看得见、点不通"。
      'chat-standard', 'chat-deep')
ON CONFLICT (channel_id, model_id) DO UPDATE SET
    upstream_model = EXCLUDED.upstream_model,
    enabled = true,
    capabilities = EXCLUDED.capabilities,
    updated_at = now();

DELETE FROM gateway.channel_model AS mapping
USING gateway.model AS model
WHERE mapping.model_id = model.id
  AND model.code = 'multimodal-basic';

INSERT INTO gateway.provider_model_price(
    id, provider_id, model_id, input_price_per_million, output_price_per_million,
    currency, internal_exchange_rate, official_source, effective_from)
SELECT gen_random_uuid(), provider.id, model.id, 0, 0,
       'CNY', 1, 'local-wiremock', '2026-01-01T00:00:00Z'
FROM gateway.provider provider
CROSS JOIN gateway.model model
WHERE provider.code = 'local-mock'
  AND model.code IN (
      'text-basic', 'text-standard', 'vision-basic', 'file-basic',
      'text-pro', 'vision-pro', 'text-max',
      'chat-standard', 'chat-deep')
ON CONFLICT (provider_id, model_id, effective_from) DO UPDATE SET
    input_price_per_million = 0,
    output_price_per_million = 0,
    currency = 'CNY',
    internal_exchange_rate = 1,
    official_source = 'local-wiremock';

-- Prefer configured real providers, retaining WireMock only for credential-free installs.
UPDATE gateway.channel AS real_channel
SET enabled = real_channel.encrypted_api_key IS NOT NULL
              AND octet_length(real_channel.encrypted_api_key) > 0,
    updated_at = now()
FROM gateway.provider AS real_provider
WHERE real_provider.id = real_channel.provider_id
  AND real_provider.provider_type IN ('DEEPSEEK', 'BAILIAN');

UPDATE gateway.provider AS real_provider
SET enabled = EXISTS (
        SELECT 1
        FROM gateway.channel AS real_channel
        WHERE real_channel.provider_id = real_provider.id
          AND real_channel.enabled
          AND real_channel.encrypted_api_key IS NOT NULL
          AND octet_length(real_channel.encrypted_api_key) > 0
    ),
    updated_at = now()
WHERE real_provider.provider_type IN ('DEEPSEEK', 'BAILIAN');

UPDATE gateway.channel AS mock_channel
SET enabled = NOT EXISTS (
        SELECT 1
        FROM gateway.provider AS real_provider
        JOIN gateway.channel AS real_channel
          ON real_channel.provider_id = real_provider.id
        WHERE real_provider.provider_type IN ('DEEPSEEK', 'BAILIAN')
          AND real_provider.enabled
          AND real_channel.enabled
          AND real_channel.encrypted_api_key IS NOT NULL
          AND octet_length(real_channel.encrypted_api_key) > 0
    ),
    updated_at = now()
FROM gateway.provider AS mock_provider
WHERE mock_provider.id = mock_channel.provider_id
  AND mock_provider.code = 'local-mock';

UPDATE gateway.provider AS mock_provider
SET enabled = NOT EXISTS (
        SELECT 1
        FROM gateway.provider AS real_provider
        JOIN gateway.channel AS real_channel
          ON real_channel.provider_id = real_provider.id
        WHERE real_provider.provider_type IN ('DEEPSEEK', 'BAILIAN')
          AND real_provider.enabled
          AND real_channel.enabled
          AND real_channel.encrypted_api_key IS NOT NULL
          AND octet_length(real_channel.encrypted_api_key) > 0
    ),
    updated_at = now()
WHERE mock_provider.code = 'local-mock';

COMMIT;
