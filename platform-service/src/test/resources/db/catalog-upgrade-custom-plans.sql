-- Run after platform V60 in an isolated test database.
UPDATE platform.package_plan_version
SET status = 'RETIRED', retired_at = now()
WHERE id = '00000000-0000-4000-8000-000000000102';

INSERT INTO platform.package_plan_version
SELECT (jsonb_populate_record(NULL::platform.package_plan_version,
    to_jsonb(old) || jsonb_build_object(
        'id', '11111111-1111-4111-8111-111111111111', 'version', 2,
        'status', 'DRAFT', 'published_at', NULL, 'retired_at', NULL,
        'price_amount', 12300, 'tokens_per_minute', 99))).*
FROM platform.package_plan_version old
WHERE old.id = '00000000-0000-4000-8000-000000000102';

INSERT INTO platform.package_plan_version_model
SELECT (jsonb_populate_record(NULL::platform.package_plan_version_model,
    to_jsonb(old) || jsonb_build_object(
        'id', gen_random_uuid(),
        'package_plan_version_id', '11111111-1111-4111-8111-111111111111'))).*
FROM platform.package_plan_version_model old
WHERE old.package_plan_version_id = '00000000-0000-4000-8000-000000000102';

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id = '11111111-1111-4111-8111-111111111111';

-- The original FREE seed is still published, but an administrator owns draft v2.
INSERT INTO platform.package_plan_version
SELECT (jsonb_populate_record(NULL::platform.package_plan_version,
    to_jsonb(old) || jsonb_build_object(
        'id', '22222222-2222-4222-8222-222222222222', 'version', 2,
        'status', 'DRAFT', 'published_at', NULL, 'retired_at', NULL))).*
FROM platform.package_plan_version old
WHERE old.id = '00000000-0000-4000-8000-000000000101';

-- display_order is intentionally not unique in the administrator's catalogue.
INSERT INTO platform.package_plan(id, code, name, description, status, display_order)
VALUES ('33333333-3333-4333-8333-333333333333', 'CUSTOM_UPGRADE_TEST',
        'Custom upgrade test', 'Preserve administrator ordering', 'ACTIVE', 0);
