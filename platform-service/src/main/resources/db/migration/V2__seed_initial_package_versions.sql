ALTER TABLE platform.package_plan_version
    ADD COLUMN daily_request_limit integer,
    ADD COLUMN max_output_tokens integer,
    ADD COLUMN max_file_size_bytes bigint NOT NULL DEFAULT 20971520,
    ADD COLUMN once_per_user boolean NOT NULL DEFAULT false,
    ADD COLUMN estimated_cost_amount bigint,
    ADD COLUMN estimated_margin_ratio numeric(8, 5),
    ADD COLUMN margin_warning boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_package_version_optional_limits CHECK (
        (daily_request_limit IS NULL OR daily_request_limit > 0)
        AND (max_output_tokens IS NULL OR max_output_tokens > 0)
        AND max_file_size_bytes > 0
        AND (estimated_cost_amount IS NULL OR estimated_cost_amount >= 0)
        AND (estimated_margin_ratio IS NULL
             OR (estimated_margin_ratio >= -1000 AND estimated_margin_ratio < 1))
    );

INSERT INTO platform.package_plan(id, code, name, description, status) VALUES
    ('00000000-0000-4000-8000-000000000001', 'FREE', 'Free', 'One-time introductory package', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000002', 'STARTER', 'Starter', 'Starter text package', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000003', 'ADVANCED', 'Advanced', 'Advanced multimodal package', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000004', 'ENTERPRISE', 'Enterprise', 'Enterprise base package', 'ACTIVE');

INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning)
VALUES
    ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000001',
     1, 0, 'CNY', 100000, 30, 1, 2, 20000, 1, 0.00000, 'PUBLISHED', now(),
     20, 2000, 5242880, true, 20, NULL, false),
    ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000002',
     1, 1900, 'CNY', 5000000, 30, 3, 20, 100000, 3, 0.46900, 'PUBLISHED', now(),
     NULL, NULL, 20971520, false, 1008, 0.46947, false),
    ('00000000-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000003',
     1, 19900, 'CNY', 20000000, 30, 10, 100, 500000, 15, 0.41600, 'PUBLISHED', now(),
     NULL, NULL, 20971520, false, 11613, 0.41643, false),
    ('00000000-0000-4000-8000-000000000104', '00000000-0000-4000-8000-000000000004',
     1, 249900, 'CNY', 100000000, 30, 50, 300, 2000000, 50, 0.39200, 'PUBLISHED', now(),
     NULL, NULL, 20971520, false, 152000, 0.39176, false);

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
VALUES
    ('00000000-0000-4000-8000-000000001001', '00000000-0000-4000-8000-000000000101', 'text-basic', NULL, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001002', '00000000-0000-4000-8000-000000000101', 'vision-basic', NULL, '["TEXT","IMAGE"]'),
    ('00000000-0000-4000-8000-000000001003', '00000000-0000-4000-8000-000000000101', 'file-basic', NULL, '["TEXT","FILE"]'),

    ('00000000-0000-4000-8000-000000001011', '00000000-0000-4000-8000-000000000102', 'text-basic', NULL, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001012', '00000000-0000-4000-8000-000000000102', 'vision-basic', NULL, '["TEXT","IMAGE"]'),
    ('00000000-0000-4000-8000-000000001013', '00000000-0000-4000-8000-000000000102', 'file-basic', NULL, '["TEXT","FILE"]'),
    ('00000000-0000-4000-8000-000000001014', '00000000-0000-4000-8000-000000000102', 'text-standard', NULL, '["TEXT"]'),

    ('00000000-0000-4000-8000-000000001021', '00000000-0000-4000-8000-000000000103', 'text-basic', NULL, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001022', '00000000-0000-4000-8000-000000000103', 'vision-basic', NULL, '["TEXT","IMAGE"]'),
    ('00000000-0000-4000-8000-000000001023', '00000000-0000-4000-8000-000000000103', 'file-basic', NULL, '["TEXT","FILE"]'),
    ('00000000-0000-4000-8000-000000001024', '00000000-0000-4000-8000-000000000103', 'text-standard', NULL, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001025', '00000000-0000-4000-8000-000000000103', 'text-pro', 10000000, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001026', '00000000-0000-4000-8000-000000000103', 'vision-pro', 2000000, '["TEXT","IMAGE"]'),

    ('00000000-0000-4000-8000-000000001031', '00000000-0000-4000-8000-000000000104', 'text-basic', NULL, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001032', '00000000-0000-4000-8000-000000000104', 'vision-basic', NULL, '["TEXT","IMAGE"]'),
    ('00000000-0000-4000-8000-000000001033', '00000000-0000-4000-8000-000000000104', 'file-basic', NULL, '["TEXT","FILE"]'),
    ('00000000-0000-4000-8000-000000001034', '00000000-0000-4000-8000-000000000104', 'text-standard', NULL, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001035', '00000000-0000-4000-8000-000000000104', 'text-pro', NULL, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001036', '00000000-0000-4000-8000-000000000104', 'vision-pro', NULL, '["TEXT","IMAGE"]'),
    ('00000000-0000-4000-8000-000000001037', '00000000-0000-4000-8000-000000000104', 'text-max', 20000000, '["TEXT"]');

CREATE OR REPLACE FUNCTION platform.reject_published_version_mutation()
RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'published_version_immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_package_version_immutable
BEFORE UPDATE OR DELETE ON platform.package_plan_version
FOR EACH ROW EXECUTE FUNCTION platform.reject_published_version_mutation();

CREATE OR REPLACE FUNCTION platform.reject_published_model_mutation()
RETURNS trigger AS $$
DECLARE
    parent_status varchar(20);
    parent_id uuid;
BEGIN
    parent_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.package_plan_version_id ELSE NEW.package_plan_version_id END;
    SELECT status INTO parent_status
    FROM platform.package_plan_version
    WHERE id = parent_id;
    IF parent_status = 'PUBLISHED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'published_version_immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_package_version_model_immutable
BEFORE INSERT OR UPDATE OR DELETE ON platform.package_plan_version_model
FOR EACH ROW EXECUTE FUNCTION platform.reject_published_model_mutation();
