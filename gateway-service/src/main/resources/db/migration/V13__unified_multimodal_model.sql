ALTER TABLE gateway.model
    ADD COLUMN routing_strategy varchar(16) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN routing_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_model_routing_strategy
        CHECK (routing_strategy IN ('DIRECT', 'COMPOSITE')),
    ADD CONSTRAINT ck_model_routing_policy
        CHECK (
            jsonb_typeof(routing_policy) = 'object'
            AND (
                routing_strategy = 'DIRECT'
                OR routing_policy ?& ARRAY[
                    'textModel', 'imageModel', 'fileModel',
                    'mixedFinalModel', 'extractorVersion'
                ]
            )
        );

INSERT INTO gateway.model(
    id, code, display_name, description, capabilities,
    context_window, max_output_tokens, premium, enabled,
    requests_per_minute, max_concurrency, routing_strategy, routing_policy)
VALUES (
    '82000000-0000-4000-8000-000000000008',
    'multimodal-basic',
    'Multimodal Basic',
    'Unified text, image, and document model',
    '["TEXT","IMAGE","FILE"]',
    260096,
    32768,
    false,
    true,
    60,
    10,
    'COMPOSITE',
    '{"textModel":"text-basic","imageModel":"vision-basic","fileModel":"file-basic","mixedFinalModel":"vision-basic","extractorVersion":"multimodal-v1"}'
);

INSERT INTO gateway.model_alias(id, model_id, alias)
VALUES (
    '83000000-0000-4000-8000-000000000008',
    '82000000-0000-4000-8000-000000000008',
    'multimodal-basic'
);

CREATE TABLE gateway.attachment_extraction_cache (
    id uuid PRIMARY KEY,
    source_file_id uuid NOT NULL REFERENCES gateway.file_object(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    file_sha256 varchar(64) NOT NULL,
    extractor_version varchar(64) NOT NULL,
    policy_version varchar(64) NOT NULL,
    normalized_text text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, owner_user_id, file_sha256, extractor_version, policy_version),
    CONSTRAINT ck_attachment_extraction_cache_values CHECK (
        length(file_sha256) = 64
        AND length(extractor_version) > 0
        AND length(policy_version) > 0
        AND length(normalized_text) <= 400000
        AND jsonb_typeof(metadata) = 'object'
    )
);

CREATE INDEX ix_attachment_extraction_cache_file
    ON gateway.attachment_extraction_cache (source_file_id);
CREATE INDEX ix_attachment_extraction_cache_expiry
    ON gateway.attachment_extraction_cache (expires_at);

CREATE TABLE gateway.usage_stage (
    id uuid PRIMARY KEY,
    usage_log_id uuid NOT NULL REFERENCES gateway.usage_log(id) ON DELETE RESTRICT,
    stage_index integer NOT NULL,
    stage_type varchar(32) NOT NULL,
    logical_model_code varchar(128) NOT NULL,
    model_id uuid NOT NULL REFERENCES gateway.model(id) ON DELETE RESTRICT,
    provider_id uuid NOT NULL REFERENCES gateway.provider(id) ON DELETE RESTRICT,
    channel_id uuid NOT NULL REFERENCES gateway.channel(id) ON DELETE RESTRICT,
    upstream_model varchar(128) NOT NULL,
    input_tokens bigint NOT NULL,
    output_tokens bigint NOT NULL,
    total_tokens bigint NOT NULL,
    usage_estimated boolean NOT NULL,
    duration_ms bigint NOT NULL,
    outcome varchar(20) NOT NULL,
    error_category varchar(64),
    provider_cost_amount bigint,
    provider_cost_currency char(3),
    price_estimated boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (usage_log_id, stage_index),
    CONSTRAINT ck_usage_stage_values CHECK (
        stage_index >= 0
        AND input_tokens >= 0
        AND output_tokens >= 0
        AND total_tokens = input_tokens + output_tokens
        AND duration_ms >= 0
        AND (provider_cost_amount IS NULL OR provider_cost_amount >= 0)
        AND ((provider_cost_amount IS NULL) = (provider_cost_currency IS NULL))
    ),
    CONSTRAINT ck_usage_stage_type
        CHECK (stage_type IN ('FINAL_RESPONSE', 'FILE_ANALYSIS')),
    CONSTRAINT ck_usage_stage_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_usage_stage_usage_log
    ON gateway.usage_stage (usage_log_id, stage_index);
CREATE INDEX ix_usage_stage_created
    ON gateway.usage_stage (created_at DESC);

WITH qualifying_packages AS (
    SELECT package.id,
           CASE
               WHEN count(*) FILTER (
                   WHERE component.model_token_quota IS NOT NULL) = 3
               THEN min(component.model_token_quota)
               ELSE NULL
           END AS composite_token_quota
    FROM gateway.package_instance package
    CROSS JOIN LATERAL (
        SELECT model ->> 'modelCode' AS model_code,
               CASE
                   WHEN model -> 'modelTokenQuota' IS NULL
                        OR model -> 'modelTokenQuota' = 'null'::jsonb
                   THEN NULL
                   ELSE (model ->> 'modelTokenQuota')::bigint
               END AS model_token_quota
        FROM jsonb_array_elements(package.allowed_models) model
        WHERE model ->> 'modelCode' IN ('text-basic', 'vision-basic', 'file-basic')
    ) component
    GROUP BY package.id
    HAVING count(DISTINCT component.model_code) = 3
), composite_permissions AS (
    SELECT id,
           jsonb_build_object(
               'modelCode', 'multimodal-basic',
               'modelTokenQuota', composite_token_quota,
               'capabilities', jsonb_build_array('TEXT', 'IMAGE', 'FILE')
           ) AS permission
    FROM qualifying_packages
)
UPDATE gateway.package_instance package
SET allowed_models = package.allowed_models || permissions.permission,
    updated_at = now()
FROM composite_permissions permissions
WHERE package.id = permissions.id
  AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements(package.allowed_models) existing
      WHERE existing ->> 'modelCode' = 'multimodal-basic'
  );

INSERT INTO gateway.package_instance_model_quota(
    id, package_instance_id, model_code, token_quota)
SELECT gen_random_uuid(), component.package_instance_id,
       'multimodal-basic', min(component.token_quota)
FROM gateway.package_instance_model_quota component
WHERE component.model_code IN ('text-basic', 'vision-basic', 'file-basic')
GROUP BY component.package_instance_id
HAVING count(DISTINCT component.model_code) = 3
ON CONFLICT (package_instance_id, model_code) DO NOTHING;

UPDATE gateway.api_key key
SET allowed_models = key.allowed_models || '"multimodal-basic"'::jsonb,
    updated_at = now()
WHERE key.allowed_models @> '["text-basic","vision-basic","file-basic"]'::jsonb
  AND NOT key.allowed_models @> '["multimodal-basic"]'::jsonb;
