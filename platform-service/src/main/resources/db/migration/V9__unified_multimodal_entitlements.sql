-- ALTER TABLE takes an exclusive transaction-scoped lock, so published versions cannot
-- be changed concurrently while this one-time catalog migration bypasses the user trigger.
ALTER TABLE platform.package_plan_version_model
    DISABLE TRIGGER trg_package_version_model_immutable;

WITH qualifying_versions AS (
    SELECT package_plan_version_id,
           CASE
               WHEN count(*) FILTER (WHERE model_token_quota IS NOT NULL) = 3
               THEN min(model_token_quota)
               ELSE NULL
           END AS composite_token_quota
    FROM platform.package_plan_version_model
    WHERE model_code IN ('text-basic', 'vision-basic', 'file-basic')
    GROUP BY package_plan_version_id
    HAVING count(DISTINCT model_code) = 3
)
INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(), package_plan_version_id, 'multimodal-basic',
       composite_token_quota, '["TEXT","IMAGE","FILE"]'::jsonb
FROM qualifying_versions
ON CONFLICT (package_plan_version_id, model_code) DO NOTHING;

ALTER TABLE platform.package_plan_version_model
    ENABLE TRIGGER trg_package_version_model_immutable;
