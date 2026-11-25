UPDATE gateway.channel_model AS mapping
SET upstream_model = route.upstream_model,
    updated_at = now()
FROM (VALUES
    ('deepseek', 'text-standard', 'deepseek-chat'),
    ('deepseek', 'text-pro', 'deepseek-reasoner'),
    ('bailian', 'text-basic', 'qwen-turbo'),
    ('bailian', 'text-pro', 'qwen-plus'),
    ('bailian', 'text-max', 'qwen-max'),
    ('bailian', 'vision-basic', 'qwen-vl-plus'),
    ('bailian', 'vision-pro', 'qwen-vl-max'),
    ('bailian', 'file-basic', 'qwen-long')
) AS route(provider_code, model_code, upstream_model)
JOIN gateway.provider AS provider ON provider.code = route.provider_code
JOIN gateway.channel AS channel ON channel.provider_id = provider.id
JOIN gateway.model AS model ON model.code = route.model_code
WHERE mapping.channel_id = channel.id
  AND mapping.model_id = model.id;

INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT '85000000-0000-4000-8000-000000000009', channel.id, model.id,
       'deepseek-chat', true, '["TEXT"]'::jsonb
FROM gateway.provider AS provider
JOIN gateway.channel AS channel ON channel.provider_id = provider.id
CROSS JOIN gateway.model AS model
WHERE provider.code = 'deepseek'
  AND channel.code = 'deepseek-primary'
  AND model.code = 'text-basic'
ON CONFLICT (channel_id, model_id) DO UPDATE
SET upstream_model = EXCLUDED.upstream_model,
    enabled = true,
    capabilities = EXCLUDED.capabilities,
    updated_at = now();
