-- 让"纯音频"（AUDIO_SECOND）套餐真正可用
--
-- WHY. 平台代码早就把 AUDIO_SECOND 当作合法维度使用：
--   * `PackageVersionService.resolveDimension`：一个版本若只挂音频别名
--     （AUDIO_TRANSCRIBE / AUDIO_SYNTHESIS / LIP_SYNC），返回的就是 **AUDIO_SECOND**；
--   * `PackageRateCeilingPolicy`、`PublishedPlanMarginMonitor`、`OperatingFactIngestionService`
--     都把 AUDIO_SECOND 列在合法取值里；
--   * 网关的 `AudioEconomicsEvidenceWriter` 确实会以 AUDIO_SECOND 往 economics outbox 写事件。
--
-- 但**四处**数据库约束都没跟着扩展，导致这条路径在数据库层被挡死。四处缺一不可：
--
--   (1) platform.package_plan_version        ck_package_version_usage_dimension
--   (2) platform.package_plan_version        ck_package_version_audio_seconds
--   (3) platform.operating_fact              ck_operating_fact_usage_dimension
--   (4) platform.operating_daily_aggregate   ck_operating_daily_usage_dimension
--
-- 后果（两条都被代码路径证实，不是推测）：
--
-- **一、纯音频套餐建不出来。** 只补 (1) 是不够的 —— 实测（在生产库副本上）：
--   * 带 audio_seconds_quota 插入 → 撞 (2)：
--     `ck_package_version_audio_seconds CHECK (audio_seconds_quota IS NULL OR
--      (audio_seconds_quota > 0 AND usage_dimension = 'VIDEO_SECOND'))`
--     —— 它写死了"音频秒额度只属于 VIDEO_SECOND"，与"纯音频版本"直接矛盾；
--   * 不带 audio_seconds_quota 插入 → 撞 `ck_package_version_values` 的
--     `total_token_quota > 0`：纯音频版本的额度在 audio_seconds_quota 那一列，
--     total_token_quota 没有意义，却被迫 > 0。
--   而原生 check violation 不被 `PackageCatalogControllerAdvice` 处理
--   （它只认 ResponseStatusException 与 MarginPolicyException）→ 运维看到 **500** 而非 422。
--   这使 `docs/模型接口本地联调文档.md`（要求新建/发布 AUDIO_SECOND 套餐并发放）
--   与 `docs/配音音色列表接口文档.md` 不可执行。
--
-- **二、音频经营事实永远入不了账。** 网关发来的 AUDIO_SECOND 事件撞 (3) → 500 →
--   网关 outbox 重试到 `GATEWAY_ECONOMICS_MAXIMUM_ATTEMPTS`(10) 后置 **DEAD**，
--   而恢复入口只重投 `usage_economics_event_pending = true` 的行，
--   **DEAD 不会被自动复活** → 音频收入/成本不进经营报表。
--
-- 注意这与 V103 那条路**不同**：V103 给在售视频套餐加音频秒额度、主维度仍是
-- VIDEO_SECOND（方案 C 的混合形态），(2) 正是为它写的，本迁移**保留** VIDEO_SECOND
-- 分支，只额外放行 AUDIO_SECOND 自己。
--
-- 做法：DROP ... IF EXISTS 再 ADD，因此对"本地链形态"与"生产快照形态"都安全 ——
-- 不假设约束当前是否叫这个名字、是否被别的迁移改过。
--
-- 对现有数据的影响：生产实测**没有任何 AUDIO_SECOND 版本**（0 条），
-- `operating_fact` 也没有 AUDIO_SECOND 行，因此放开值域不改变任何现有行；
-- 它只是把"代码声称支持、数据库拒绝"这条矛盾抹平。

-- (1) 维度白名单：三处都加上 AUDIO_SECOND
ALTER TABLE platform.package_plan_version
    DROP CONSTRAINT IF EXISTS ck_package_version_usage_dimension,
    ADD CONSTRAINT ck_package_version_usage_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- (2) 音频秒额度：原条件把额度锁死在 VIDEO_SECOND。
--     改为"额度归承载音频秒的那个维度所有" —— VIDEO_SECOND（方案 C 混合形态）
--     或 AUDIO_SECOND（纯音频形态）都可以带它。
ALTER TABLE platform.package_plan_version
    DROP CONSTRAINT IF EXISTS ck_package_version_audio_seconds,
    ADD CONSTRAINT ck_package_version_audio_seconds
        CHECK (audio_seconds_quota IS NULL
            OR (audio_seconds_quota > 0
                AND usage_dimension IN ('VIDEO_SECOND', 'AUDIO_SECOND')));

-- (3) 版本数值：原条件一律要求 total_token_quota > 0，而纯音频版本的额度
--     在 audio_seconds_quota 那一列。改为按维度各自要求：秒维度至少有一个
--     秒额度列 > 0（视频秒或音频秒），其它维度仍要求 total_token_quota > 0。
--     注意**不放松其它任何一项**（价格/时长/密钥数/速率/并发/目标毛利）。
ALTER TABLE platform.package_plan_version
    DROP CONSTRAINT IF EXISTS ck_package_version_values,
    ADD CONSTRAINT ck_package_version_values
        CHECK (price_amount >= 0
            AND duration_days > 0
            AND max_api_keys > 0
            AND requests_per_minute > 0
            AND tokens_per_minute > 0
            AND max_concurrency > 0
            AND target_margin_ratio >= 0
            AND target_margin_ratio < 1
            AND (CASE
                     WHEN usage_dimension IN ('VIDEO_SECOND', 'AUDIO_SECOND')
                         THEN coalesce(total_token_quota, 0)
                              + coalesce(audio_seconds_quota, 0) > 0
                     ELSE total_token_quota > 0
                 END));

-- (4) 经营事实与日聚合的维度白名单
ALTER TABLE platform.operating_fact
    DROP CONSTRAINT IF EXISTS ck_operating_fact_usage_dimension,
    ADD CONSTRAINT ck_operating_fact_usage_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

ALTER TABLE platform.operating_daily_aggregate
    DROP CONSTRAINT IF EXISTS ck_operating_daily_usage_dimension,
    ADD CONSTRAINT ck_operating_daily_usage_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- 断言：不只是"约束文本里出现了 AUDIO_SECOND"，而是**真的能插入一条纯音频版本**。
-- 这是本迁移存在的全部理由，所以直接验证行为而不是验证文本。
DO $$
DECLARE
    plan_id      uuid;
    probe_id     uuid := '00000000-0000-4000-8000-0000a0d10999'::uuid;
    inserted     integer;
    missing      integer;
BEGIN
    -- 四处的维度白名单都必须含 AUDIO_SECOND
    SELECT count(*) INTO missing
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = 'platform'
      AND constraint_row.conname IN (
            'ck_package_version_usage_dimension',
            'ck_operating_fact_usage_dimension',
            'ck_operating_daily_usage_dimension')
      AND pg_get_constraintdef(constraint_row.oid) NOT LIKE '%AUDIO_SECOND%';
    IF missing > 0 THEN
        RAISE EXCEPTION 'audio_second_missing_from_dimension_check:count=%', missing;
    END IF;

    -- 音频秒额度必须接受 AUDIO_SECOND 维度
    SELECT count(*) INTO missing
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = 'platform'
      AND constraint_row.conname = 'ck_package_version_audio_seconds'
      AND pg_get_constraintdef(constraint_row.oid) NOT LIKE '%AUDIO_SECOND%';
    IF missing > 0 THEN
        RAISE EXCEPTION 'audio_seconds_check_does_not_accept_audio_second';
    END IF;

    -- 行为验证：拿一个真实的 plan 插一条纯音频 DRAFT 版本，再删掉。
    -- 用 DO 块是为了即便中途抛错也不留行（异常会中止整个事务）。
    SELECT id INTO plan_id FROM platform.package_plan
    WHERE code = 'VIDEO_LITE' LIMIT 1;
    IF plan_id IS NULL THEN
        RAISE EXCEPTION 'audio_second_probe_needs_a_plan_to_attach_to';
    END IF;

    INSERT INTO platform.package_plan_version(
        id, package_plan_id, version, price_amount, currency, total_token_quota,
        duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
        max_concurrency, target_margin_ratio, status, max_file_size_bytes,
        once_per_user, low_stock_threshold, usage_dimension, audio_seconds_quota)
    VALUES (probe_id, plan_id, 999999, 1000, 'CNY', 0, 30, 1, 10, 60, 1,
            0.35000, 'DRAFT', 2097152, false, 0, 'AUDIO_SECOND', 100);
    GET DIAGNOSTICS inserted = ROW_COUNT;
    IF inserted <> 1 THEN
        RAISE EXCEPTION 'audio_second_probe_insert_did_not_take_effect';
    END IF;

    DELETE FROM platform.package_plan_version WHERE id = probe_id;
END $$;

