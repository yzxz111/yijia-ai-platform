-- 把 video-minimax 加进 5 个视频套餐（替换发布版本，而不是原地改）。
--
-- 为什么不能直接插一行：platform.reject_published_model_mutation 是 BEFORE INSERT
-- 触发器，父版本处于 PUBLISHED 时一刀拒绝（`published_version_immutable`）。
-- 这是设计：已发布版本是对客户承诺过的价目表，它只能被换代，不能被改写。
-- 而 uq_published_package_plan_version 又保证一个计划只能有一个 PUBLISHED 版本，
-- 所以顺序是被逼出来的：先退休旧版本 → 新建 DRAFT（复制旧版本的每一个字段）
-- → 复制模型行并补上 video-minimax → 再发布。
-- 这套顺序与 V54/V56/V61/V63/V64/V84 完全一致。
--
-- 本次的唯一行为差异就是每个版本多一个 video-minimax 模型行。价格、秒额度、时长、
-- 密钥数、速率、并发、库存、维度、每模型自额度与已存毛利估计**一律从被替换的版本
-- 逐列复制**，因此不可能因为重打一个字面量而漂移。
--
-- 关于 model_token_quota（video-minimax 那一行）：留空。
--   * 视频维度的自额度是可选的：网关 QuotaReservationRepository.lockModelQuota
--     找不到该行时退回套餐总额度桶（QuotaService.reserveWithModelQuota），
--     因此留空 == 与 VIDEO_CREATOR / VIDEO_STARTER 现有行完全同形；
--   * 填一个数字反而是新增约束（额度不足时 422 quota_exhausted），那属于定价决定。
--     个别套餐要单独限额，在控制台改该版本这一行即可。
--
-- video-minimax 的能力串与其它视频行逐字一致；网关目录要的 VIDEO 能力来自
-- gateway.model（网关 V99 写的），不依赖这里。

-- ---------------------------------------------------------------------------
-- 1. 退休当前 5 个发布中的视频版本
--
-- 不可变触发器只允许这一种改写：status -> RETIRED（附带 retired_at）。
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'RETIRED', retired_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000321',  -- VIDEO_STARTER v3
        '00000000-0000-4000-8000-000000000322',  -- VIDEO_CREATOR v3
        '00000000-0000-4000-8000-000000000323',  -- VIDEO_PRO v4
        '00000000-0000-4000-8000-000000000401',  -- VIDEO_LITE v1
        '00000000-0000-4000-8000-000000000402')  -- VIDEO_LITE_ANNUAL v1
  AND status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- 2. 建替代版本（DRAFT），除版本号外全部字段从旧版本复制
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at, retired_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning, stock_total,
    low_stock_threshold, max_purchases_per_user, usage_dimension)
SELECT replacement.id, old.package_plan_id,
       (SELECT max(existing.version) + 1 FROM platform.package_plan_version existing
        WHERE existing.package_plan_id = old.package_plan_id),
       old.price_amount, old.currency, old.total_token_quota, old.duration_days,
       old.max_api_keys, old.requests_per_minute, old.tokens_per_minute,
       old.max_concurrency, old.target_margin_ratio, 'DRAFT', NULL, NULL,
       old.daily_request_limit, old.max_output_tokens, old.max_file_size_bytes,
       old.once_per_user, old.estimated_cost_amount, old.estimated_margin_ratio,
       old.margin_warning, old.stock_total, old.low_stock_threshold,
       old.max_purchases_per_user, old.usage_dimension
FROM (VALUES
        ('00000000-0000-4000-8000-000000000331'::uuid, '00000000-0000-4000-8000-000000000321'::uuid),
        ('00000000-0000-4000-8000-000000000332'::uuid, '00000000-0000-4000-8000-000000000322'::uuid),
        ('00000000-0000-4000-8000-000000000333'::uuid, '00000000-0000-4000-8000-000000000323'::uuid),
        ('00000000-0000-4000-8000-000000000334'::uuid, '00000000-0000-4000-8000-000000000401'::uuid),
        ('00000000-0000-4000-8000-000000000335'::uuid, '00000000-0000-4000-8000-000000000402'::uuid)
     ) AS replacement(id, replaces)
JOIN platform.package_plan_version old ON old.id = replacement.replaces;

-- ---------------------------------------------------------------------------
-- 3. 复制模型行（逐字保留各自额度与能力），并补上 video-minimax
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(), replacement.id, old.model_code, old.model_token_quota,
       old.capabilities
FROM (VALUES
        ('00000000-0000-4000-8000-000000000331'::uuid, '00000000-0000-4000-8000-000000000321'::uuid),
        ('00000000-0000-4000-8000-000000000332'::uuid, '00000000-0000-4000-8000-000000000322'::uuid),
        ('00000000-0000-4000-8000-000000000333'::uuid, '00000000-0000-4000-8000-000000000323'::uuid),
        ('00000000-0000-4000-8000-000000000334'::uuid, '00000000-0000-4000-8000-000000000401'::uuid),
        ('00000000-0000-4000-8000-000000000335'::uuid, '00000000-0000-4000-8000-000000000402'::uuid)
     ) AS replacement(id, replaces)
JOIN platform.package_plan_version_model old
  ON old.package_plan_version_id = replacement.replaces;

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
VALUES
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000331', 'video-minimax', NULL, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000332', 'video-minimax', NULL, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000333', 'video-minimax', NULL, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000334', 'video-minimax', NULL, '["TEXT","IMAGE","VIDEO"]'),
    (gen_random_uuid(), '00000000-0000-4000-8000-000000000335', 'video-minimax', NULL, '["TEXT","IMAGE","VIDEO"]');

-- ---------------------------------------------------------------------------
-- 4. 发布替代版本
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id IN (
        '00000000-0000-4000-8000-000000000331',
        '00000000-0000-4000-8000-000000000332',
        '00000000-0000-4000-8000-000000000333',
        '00000000-0000-4000-8000-000000000334',
        '00000000-0000-4000-8000-000000000335')
  AND status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 5. 断言
--
-- 写入受不可变触发器保护的表的迁移必须自己证明结果。这里证明四件事：
-- 旧版本确实退休、新版本确实发布且每计划只有一个发布版本、每个新版本都含
-- video-minimax、以及**每个新版本的模型行数恰好比旧版本多 1**（多一行就是
-- 本次的全部意图；多两行说明 WHERE/JOIN 写错，复制了不该复制的东西）。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken integer;
BEGIN
    -- 旧版本必须都已退休
    SELECT count(*) INTO broken
    FROM platform.package_plan_version
    WHERE id IN ('00000000-0000-4000-8000-000000000321',
                 '00000000-0000-4000-8000-000000000322',
                 '00000000-0000-4000-8000-000000000323',
                 '00000000-0000-4000-8000-000000000401',
                 '00000000-0000-4000-8000-000000000402')
      AND status <> 'RETIRED';
    IF broken > 0 THEN
        RAISE EXCEPTION 'replaced_versions_not_retired:count=%', broken;
    END IF;

    -- 每个计划只能有一个发布版本（uq_published_package_plan_version 也会拦，
    -- 但这里给出一个能读懂的错误码）
    SELECT count(*) INTO broken
    FROM (
        SELECT plan.id
        FROM platform.package_plan plan
        JOIN platform.package_plan_version version ON version.package_plan_id = plan.id
        WHERE plan.code IN ('VIDEO_STARTER', 'VIDEO_CREATOR', 'VIDEO_PRO',
                            'VIDEO_LITE', 'VIDEO_LITE_ANNUAL')
          AND version.status = 'PUBLISHED'
        GROUP BY plan.id
        HAVING count(*) <> 1
    ) bad;
    IF broken > 0 THEN
        RAISE EXCEPTION 'plan_must_have_exactly_one_published_version:plans=%', broken;
    END IF;

    -- 每个新版本都必须含 video-minimax
    SELECT count(*) INTO broken
    FROM (VALUES
            ('00000000-0000-4000-8000-000000000331'::uuid),
            ('00000000-0000-4000-8000-000000000332'::uuid),
            ('00000000-0000-4000-8000-000000000333'::uuid),
            ('00000000-0000-4000-8000-000000000334'::uuid),
            ('00000000-0000-4000-8000-000000000335'::uuid)
         ) AS created(id)
    WHERE NOT EXISTS (
            SELECT 1 FROM platform.package_plan_version_model model
            WHERE model.package_plan_version_id = created.id
              AND model.model_code = 'video-minimax');
    IF broken > 0 THEN
        RAISE EXCEPTION 'video_minimax_missing_from_new_version:count=%', broken;
    END IF;

    -- 新版本模型行数 = 旧版本 + 1（每个配对都查）
    SELECT count(*) INTO broken
    FROM (VALUES
            ('00000000-0000-4000-8000-000000000331'::uuid, '00000000-0000-4000-8000-000000000321'::uuid),
            ('00000000-0000-4000-8000-000000000332'::uuid, '00000000-0000-4000-8000-000000000322'::uuid),
            ('00000000-0000-4000-8000-000000000333'::uuid, '00000000-0000-4000-8000-000000000323'::uuid),
            ('00000000-0000-4000-8000-000000000334'::uuid, '00000000-0000-4000-8000-000000000401'::uuid),
            ('00000000-0000-4000-8000-000000000335'::uuid, '00000000-0000-4000-8000-000000000402'::uuid)
         ) AS pair(created_id, old_id)
    WHERE (SELECT count(*) FROM platform.package_plan_version_model m
            WHERE m.package_plan_version_id = pair.created_id)
          <> (SELECT count(*) FROM platform.package_plan_version_model m
               WHERE m.package_plan_version_id = pair.old_id) + 1;
    IF broken > 0 THEN
        RAISE EXCEPTION 'model_row_count_is_not_old_plus_one:count=%', broken;
    END IF;
END $$;
