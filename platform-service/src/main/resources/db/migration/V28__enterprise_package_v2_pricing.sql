INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning)
VALUES (
    '00000000-0000-4000-8000-000000000204',
    '00000000-0000-4000-8000-000000000004',
    2, 24900, 'CNY', 100000000,
    30, 50, 300, 2000000,
    50, 0.40000, 'DRAFT', NULL,
    NULL, NULL, 20971520, false,
    14160, 0.43133, false);

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
VALUES
    ('00000000-0000-4000-8000-000000001041', '00000000-0000-4000-8000-000000000204', 'text-basic', 60000000, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001042', '00000000-0000-4000-8000-000000000204', 'vision-basic', 5000000, '["TEXT","IMAGE"]'),
    ('00000000-0000-4000-8000-000000001043', '00000000-0000-4000-8000-000000000204', 'file-basic', 5000000, '["TEXT","FILE"]'),
    ('00000000-0000-4000-8000-000000001044', '00000000-0000-4000-8000-000000000204', 'multimodal-basic', 3000000, '["TEXT","IMAGE","FILE"]'),
    ('00000000-0000-4000-8000-000000001045', '00000000-0000-4000-8000-000000000204', 'text-standard', 10000000, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001046', '00000000-0000-4000-8000-000000000204', 'text-pro', 10000000, '["TEXT"]'),
    ('00000000-0000-4000-8000-000000001047', '00000000-0000-4000-8000-000000000204', 'vision-pro', 5000000, '["TEXT","IMAGE"]'),
    ('00000000-0000-4000-8000-000000001048', '00000000-0000-4000-8000-000000000204', 'text-max', 2000000, '["TEXT"]');

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id = '00000000-0000-4000-8000-000000000204'
  AND status = 'DRAFT';
