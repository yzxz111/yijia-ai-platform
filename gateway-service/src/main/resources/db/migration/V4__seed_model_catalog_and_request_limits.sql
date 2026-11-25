ALTER TABLE gateway.model
    ADD COLUMN requests_per_minute integer,
    ADD COLUMN max_concurrency integer,
    ADD CONSTRAINT ck_model_request_limits CHECK (
        (requests_per_minute IS NULL OR requests_per_minute > 0)
        AND (max_concurrency IS NULL OR max_concurrency > 0)
    );

ALTER TABLE gateway.api_key
    ADD COLUMN requests_per_minute integer,
    ADD COLUMN max_concurrency integer,
    ADD CONSTRAINT ck_api_key_request_limits CHECK (
        (requests_per_minute IS NULL OR requests_per_minute > 0)
        AND (max_concurrency IS NULL OR max_concurrency > 0)
    );

CREATE TABLE gateway.user_request_limit (
    user_id uuid PRIMARY KEY,
    requests_per_minute integer,
    max_concurrency integer,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_request_limits CHECK (
        (requests_per_minute IS NULL OR requests_per_minute > 0)
        AND (max_concurrency IS NULL OR max_concurrency > 0)
    )
);

CREATE TABLE gateway.request_limit_fallback_event (
    id uuid PRIMARY KEY,
    api_key_id uuid NOT NULL,
    user_id uuid NOT NULL,
    model_code varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_request_limit_fallback_event_window
    ON gateway.request_limit_fallback_event (user_id, created_at);

CREATE INDEX ix_request_limit_fallback_event_api_window
    ON gateway.request_limit_fallback_event (api_key_id, created_at);

CREATE INDEX ix_request_limit_fallback_event_model_window
    ON gateway.request_limit_fallback_event (model_code, created_at);

CREATE INDEX ix_request_limit_fallback_event_cleanup
    ON gateway.request_limit_fallback_event (created_at);

CREATE TABLE gateway.request_limit_fallback_lease (
    lease_id uuid PRIMARY KEY,
    api_key_id uuid NOT NULL,
    user_id uuid NOT NULL,
    model_code varchar(128) NOT NULL,
    channel_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    released_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_request_limit_fallback_lease_active
    ON gateway.request_limit_fallback_lease (user_id, expires_at)
    WHERE released_at IS NULL;

CREATE INDEX ix_request_limit_fallback_lease_api_active
    ON gateway.request_limit_fallback_lease (api_key_id, expires_at)
    WHERE released_at IS NULL;

CREATE INDEX ix_request_limit_fallback_lease_model_active
    ON gateway.request_limit_fallback_lease (model_code, expires_at)
    WHERE released_at IS NULL;

CREATE INDEX ix_request_limit_fallback_lease_channel_active
    ON gateway.request_limit_fallback_lease (channel_id, expires_at)
    WHERE released_at IS NULL;

CREATE INDEX ix_request_limit_fallback_lease_cleanup
    ON gateway.request_limit_fallback_lease (expires_at);

CREATE OR REPLACE FUNCTION gateway.acquire_request_limit_fallback(
    p_lease_id uuid,
    p_api_key_id uuid,
    p_user_id uuid,
    p_model_code varchar,
    p_channel_id uuid,
    p_rpm_limit integer,
    p_concurrency_limit integer,
    p_expires_at timestamptz)
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    window_start timestamptz := date_trunc('minute', now());
    scope_lock bigint;
BEGIN
    FOR scope_lock IN
        SELECT DISTINCT lock_id
        FROM unnest(ARRAY[
            hashtextextended('api-key:' || p_api_key_id::text, 0),
            hashtextextended('user:' || p_user_id::text, 0),
            hashtextextended('model:' || p_model_code, 0),
            hashtextextended('channel:' || p_channel_id::text, 0)
        ]) AS scope_locks(lock_id)
        ORDER BY lock_id
    LOOP
        PERFORM pg_advisory_xact_lock(scope_lock);
    END LOOP;

    DELETE FROM gateway.request_limit_fallback_lease
    WHERE lease_id IN (
        SELECT lease_id
        FROM gateway.request_limit_fallback_lease
        WHERE expires_at <= now()
        ORDER BY expires_at
        LIMIT 100
        FOR UPDATE SKIP LOCKED
    );

    DELETE FROM gateway.request_limit_fallback_event
    WHERE id IN (
        SELECT id
        FROM gateway.request_limit_fallback_event
        WHERE created_at < window_start
        ORDER BY created_at
        LIMIT 100
        FOR UPDATE SKIP LOCKED
    );

    IF (SELECT count(*) FROM gateway.request_limit_fallback_event
        WHERE api_key_id = p_api_key_id AND created_at >= window_start) >= p_rpm_limit
       OR (SELECT count(*) FROM gateway.request_limit_fallback_event
        WHERE user_id = p_user_id AND created_at >= window_start) >= p_rpm_limit
       OR (SELECT count(*) FROM gateway.request_limit_fallback_event
        WHERE model_code = p_model_code AND created_at >= window_start) >= p_rpm_limit
       OR (SELECT count(*) FROM gateway.request_limit_fallback_lease
        WHERE released_at IS NULL AND expires_at > now()
          AND api_key_id = p_api_key_id) >= p_concurrency_limit
       OR (SELECT count(*) FROM gateway.request_limit_fallback_lease
        WHERE released_at IS NULL AND expires_at > now()
          AND user_id = p_user_id) >= p_concurrency_limit
       OR (SELECT count(*) FROM gateway.request_limit_fallback_lease
        WHERE released_at IS NULL AND expires_at > now()
          AND model_code = p_model_code) >= p_concurrency_limit
       OR (SELECT count(*) FROM gateway.request_limit_fallback_lease
        WHERE released_at IS NULL AND expires_at > now()
          AND channel_id = p_channel_id) >= p_concurrency_limit THEN
        RETURN false;
    END IF;

    INSERT INTO gateway.request_limit_fallback_event(id, api_key_id, user_id, model_code)
    VALUES (gen_random_uuid(), p_api_key_id, p_user_id, p_model_code);
    INSERT INTO gateway.request_limit_fallback_lease(
        lease_id, api_key_id, user_id, model_code, channel_id, expires_at)
    VALUES (p_lease_id, p_api_key_id, p_user_id, p_model_code, p_channel_id, p_expires_at)
    ON CONFLICT (lease_id) DO NOTHING;
    RETURN true;
END;
$$;

INSERT INTO gateway.provider(id, code, name, provider_type, base_url, enabled) VALUES
    ('81000000-0000-4000-8000-000000000001', 'deepseek', 'DeepSeek', 'DEEPSEEK',
     'https://api.deepseek.com/v1', true),
    ('81000000-0000-4000-8000-000000000002', 'bailian', 'Alibaba Bailian', 'BAILIAN',
     'https://dashscope.aliyuncs.com/compatible-mode/v1', true);

INSERT INTO gateway.model(
    id, code, display_name, capabilities, context_window, max_output_tokens,
    premium, enabled, requests_per_minute, max_concurrency) VALUES
    ('82000000-0000-4000-8000-000000000001', 'text-basic', 'Text Basic', '["TEXT"]',
     991808, 65536, false, true, 300, 50),
    ('82000000-0000-4000-8000-000000000002', 'text-standard', 'Text Standard', '["TEXT"]',
     1000000, 8192, false, true, 300, 50),
    ('82000000-0000-4000-8000-000000000003', 'vision-basic', 'Vision Basic', '["TEXT","IMAGE"]',
     260096, 32768, false, true, 120, 20),
    ('82000000-0000-4000-8000-000000000004', 'file-basic', 'File Basic', '["TEXT","FILE"]',
     10000000, 8192, false, true, 60, 10),
    ('82000000-0000-4000-8000-000000000005', 'text-pro', 'Text Pro', '["TEXT"]',
     1000000, 65536, true, true, 200, 40),
    ('82000000-0000-4000-8000-000000000006', 'vision-pro', 'Vision Pro', '["TEXT","IMAGE"]',
     260096, 32768, true, true, 100, 20),
    ('82000000-0000-4000-8000-000000000007', 'text-max', 'Text Max', '["TEXT"]',
     991808, 65536, true, true, 100, 20);

INSERT INTO gateway.model_alias(id, model_id, alias) VALUES
    ('83000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000001', 'text-basic'),
    ('83000000-0000-4000-8000-000000000002', '82000000-0000-4000-8000-000000000002', 'text-standard'),
    ('83000000-0000-4000-8000-000000000003', '82000000-0000-4000-8000-000000000003', 'vision-basic'),
    ('83000000-0000-4000-8000-000000000004', '82000000-0000-4000-8000-000000000004', 'file-basic'),
    ('83000000-0000-4000-8000-000000000005', '82000000-0000-4000-8000-000000000005', 'text-pro'),
    ('83000000-0000-4000-8000-000000000006', '82000000-0000-4000-8000-000000000006', 'vision-pro'),
    ('83000000-0000-4000-8000-000000000007', '82000000-0000-4000-8000-000000000007', 'text-max');

INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, enabled, priority, weight,
    timeout_ms, requests_per_minute, max_concurrency, last_health_status) VALUES
    ('84000000-0000-4000-8000-000000000001', '81000000-0000-4000-8000-000000000001',
     'deepseek-primary', 'DeepSeek Primary', 'PRODUCTION', 'https://api.deepseek.com/v1',
     true, 100, 100, 60000, 300, 50, 'UNKNOWN'),
    ('84000000-0000-4000-8000-000000000002', '81000000-0000-4000-8000-000000000002',
     'bailian-primary', 'Bailian Primary', 'PRODUCTION',
     'https://dashscope.aliyuncs.com/compatible-mode/v1',
     true, 90, 100, 60000, 300, 50, 'UNKNOWN');

INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities) VALUES
    ('85000000-0000-4000-8000-000000000001', '84000000-0000-4000-8000-000000000001',
     '82000000-0000-4000-8000-000000000002', 'deepseek-v4-flash', true, '["TEXT"]'),
    ('85000000-0000-4000-8000-000000000002', '84000000-0000-4000-8000-000000000001',
     '82000000-0000-4000-8000-000000000005', 'deepseek-v4-pro', true, '["TEXT"]'),
    ('85000000-0000-4000-8000-000000000003', '84000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000001', 'qwen3.7-flash', true, '["TEXT"]'),
    ('85000000-0000-4000-8000-000000000004', '84000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000003', 'qwen3-vl-flash', true, '["TEXT","IMAGE"]'),
    ('85000000-0000-4000-8000-000000000005', '84000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000004', 'qwen-long', true, '["TEXT","FILE"]'),
    ('85000000-0000-4000-8000-000000000006', '84000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000005', 'qwen3.7-plus', true, '["TEXT"]'),
    ('85000000-0000-4000-8000-000000000007', '84000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000006', 'qwen3-vl-plus', true, '["TEXT","IMAGE"]'),
    ('85000000-0000-4000-8000-000000000008', '84000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000007', 'qwen3.7-max', true, '["TEXT"]');

INSERT INTO gateway.provider_model_price(
    id, provider_id, model_id, input_price_per_million, output_price_per_million,
    currency, internal_exchange_rate, official_source, effective_from) VALUES
    ('86000000-0000-4000-8000-000000000001', '81000000-0000-4000-8000-000000000001',
     '82000000-0000-4000-8000-000000000002', 14, 28, 'USD', 7.2,
     'https://api-docs.deepseek.com/quick_start/pricing', '2026-07-30T00:00:00Z'),
    ('86000000-0000-4000-8000-000000000002', '81000000-0000-4000-8000-000000000001',
     '82000000-0000-4000-8000-000000000005', 44, 87, 'USD', 7.2,
     'https://api-docs.deepseek.com/quick_start/pricing', '2026-07-30T00:00:00Z'),
    ('86000000-0000-4000-8000-000000000003', '81000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000001', 14, 28, 'CNY', 1,
     'https://help.aliyun.com/zh/model-studio/model-pricing', '2026-07-30T00:00:00Z'),
    ('86000000-0000-4000-8000-000000000004', '81000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000003', 15, 60, 'CNY', 1,
     'https://help.aliyun.com/zh/model-studio/model-pricing', '2026-07-30T00:00:00Z'),
    ('86000000-0000-4000-8000-000000000005', '81000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000004', 50, 200, 'CNY', 1,
     'https://help.aliyun.com/zh/model-studio/model-pricing', '2026-07-30T00:00:00Z'),
    ('86000000-0000-4000-8000-000000000006', '81000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000005', 40, 160, 'CNY', 1,
     'https://help.aliyun.com/zh/model-studio/model-pricing', '2026-07-30T00:00:00Z'),
    ('86000000-0000-4000-8000-000000000007', '81000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000006', 20, 160, 'CNY', 1,
     'https://help.aliyun.com/zh/model-studio/model-pricing', '2026-07-30T00:00:00Z'),
    ('86000000-0000-4000-8000-000000000008', '81000000-0000-4000-8000-000000000002',
     '82000000-0000-4000-8000-000000000007', 250, 750, 'CNY', 1,
     'https://help.aliyun.com/zh/model-studio/model-pricing', '2026-07-30T00:00:00Z');
