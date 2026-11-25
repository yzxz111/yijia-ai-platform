-- Real Alibaba Cloud Model Studio video routes.
--
-- This is deliberately a separate channel from V4's bailian-primary. That channel
-- uses the OpenAI-compatible /compatible-mode/v1 endpoint for chat, while video uses
-- the native asynchronous /api/v1 video-synthesis and task APIs. Sharing the chat
-- base URL would produce an invalid path.
--
-- No credential is seeded. The channel remains disabled and UNKNOWN until an operator
-- stores a system-managed Beijing API key, replaces the base URL with the matching
-- workspace endpoint, enables it and completes a successful health check. The
-- client-facing catalogue intentionally does not require those route conditions: an
-- entitled model is shown before its upstream is configured. The POST /v1/videos
-- candidate query remains strict, so these rows alone do not make the model callable.

INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, enabled, priority, weight,
    timeout_ms, requests_per_minute, max_concurrency, last_health_status, settings)
SELECT
    '84000000-0000-4000-8000-000000000071', provider.id,
    'bailian-video-primary', 'Bailian Video Primary', 'PRODUCTION',
    -- A real public DashScope origin, but not sufficient for the seeded Kling routes.
    -- Before enabling, replace it with:
    -- https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com
    'https://dashscope.aliyuncs.com',
    false, 100, 100, 60000, 20, 4, 'UNKNOWN',
    '{"managed":true,"region":"cn-beijing","requiresWorkspaceEndpoint":true,"credentialOwner":"system-gateway"}'::jsonb
FROM gateway.provider provider
WHERE provider.code = 'bailian'
  AND provider.provider_type = 'BAILIAN'
ON CONFLICT (code) DO NOTHING;

-- Model ids and aliases are introduced by V40. Each upstream_model below is an
-- actual current Alibaba Cloud Model Studio model id. Wan text routes are promoted
-- to their documented image siblings by BailianVideoGenerationAdapter when a first
-- frame is supplied; Kling v3 models natively support both text and first-frame input.
INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT route.mapping_id, channel.id, model.id, route.upstream_model, true, '["VIDEO"]'::jsonb
FROM (VALUES
    ('85000000-0000-4000-8000-000000000071'::uuid,
        'video-budget', 'wan2.5-t2v-preview'),
    ('85000000-0000-4000-8000-000000000072'::uuid,
        'video-basic', 'wan2.6-t2v'),
    ('85000000-0000-4000-8000-000000000073'::uuid,
        'video-standard', 'wan2.6-t2v'),
    ('85000000-0000-4000-8000-000000000074'::uuid,
        'video-pro', 'kling/kling-v3-turbo-video-generation'),
    ('85000000-0000-4000-8000-000000000075'::uuid,
        'video-max', 'kling/kling-v3-omni-video-generation')
) AS route(mapping_id, model_code, upstream_model)
JOIN gateway.channel channel
  ON channel.code = 'bailian-video-primary'
JOIN gateway.model model
  ON model.code = route.model_code
ON CONFLICT (channel_id, model_id) DO NOTHING;
