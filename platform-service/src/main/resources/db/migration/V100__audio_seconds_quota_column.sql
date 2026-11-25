-- 方案 C 第 1 步：给套餐版本加"第二份额度列"——语音秒。
--
-- 背景（运维在本次会话拍板）：ASR 不单独出套餐，它属于视频套餐——因为
-- "提取口播 + 带时间戳字幕"正是生成视频链条的第一步，买了视频套餐的人
-- 应当能直接用。
--
-- 但今天表达不了这个诉求：一个版本只携带**一列**受管控的额度。
-- platform.package_plan_version.total_token_quota 的语义由 usage_dimension
-- 决定（TOKEN 版是 token、VIDEO_SECOND 版是秒，见 V54:31-33），而
-- PackageVersionService.resolveDimension 会把"模型维度不一致"的版本直接拒绝
-- （422 mixed_usage_dimensions_in_package）。把 AUDIO_SECOND 别名加进
-- VIDEO_SECOND 版本，等于让视频秒的桶**悄悄替音频秒买单**。
--
-- 本迁移只做第 1 步：**把缺的那一列加上**。它可空、且没有任何代码读它，
-- 因此这一步是**零行为变化**的——现有版本全部保持 NULL。
-- 后续步骤（另做）依次是：发布校验允许音频秒与视频秒并存、毛利守卫按两个
-- 维度各算一次、网关实例同步该列、结算按"本次服务的模型维度"扣对应那一列、
-- 控制台显示两份额度。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
--       （见 "Decided: a video package carries video seconds **and** audio seconds"）

-- ---------------------------------------------------------------------------
-- 1. 新列与约束
--
-- 为什么只允许按秒维度携带它：这一列是"第二份**秒**"。TOKEN 版本按 token 计，
-- IMAGE 版本按张计，两者都没有"秒"的概念；让它们填这一列只会造出一个
-- 没有任何东西会读、却会在控制台上显示出来的假额度。
--
-- 为什么必须 > 0：一个 0 秒的语音额度对客户是"这个套餐包含语音"的假承诺，
-- 对结算是永远不够用的死额度。要表达"不含语音"就留 NULL。
-- ---------------------------------------------------------------------------

ALTER TABLE platform.package_plan_version
    ADD COLUMN audio_seconds_quota bigint,
    ADD CONSTRAINT ck_package_version_audio_seconds
        CHECK (audio_seconds_quota IS NULL
               OR (audio_seconds_quota > 0
                   AND usage_dimension IN ('VIDEO_SECOND', 'AUDIO_SECOND')));

COMMENT ON COLUMN platform.package_plan_version.audio_seconds_quota IS
    'Optional SECOND quota for a version that sells video seconds and audio seconds '
    'together (方案 C). NULL = this version sells no audio. total_token_quota keeps '
    'its own meaning (video seconds for a VIDEO_SECOND version) — this column is the '
    'second bucket, so an ASR request never consumes a video second. Only a '
    'per-second version may set it; a TOKEN or IMAGE version must leave it NULL.';

-- ---------------------------------------------------------------------------
-- 2. 断言
--
-- 直接写入受约束表的迁移必须自己证明写入合法（V83:87-91 的同一做法），
-- 而且要证明**约束真的会拦**——一条写错值的 CHECK 不会让任何东西失败，
-- 只会在某天让一个 token 套餐带着"语音秒"出现在货架上。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    column_state   text;
    column_type    text;
    constraint_def text;
    probe_id       uuid;
    non_null_rows  integer;
BEGIN
    -- 2.1 列存在、可空、类型正确
    SELECT information_schema.columns.is_nullable,
           information_schema.columns.data_type
    INTO column_state, column_type
    FROM information_schema.columns
    WHERE information_schema.columns.table_schema = 'platform'
      AND information_schema.columns.table_name = 'package_plan_version'
      AND information_schema.columns.column_name = 'audio_seconds_quota';

    IF column_state IS NULL THEN
        RAISE EXCEPTION 'audio_seconds_quota_column_is_missing';
    END IF;
    -- 可空是刻意的：绝大多数版本不含语音，它们必须能留 NULL。
    IF column_state <> 'YES' THEN
        RAISE EXCEPTION 'audio_seconds_quota_must_be_nullable:actual=%', column_state;
    END IF;
    IF column_type <> 'bigint' THEN
        RAISE EXCEPTION 'audio_seconds_quota_must_be_bigint:actual=%', column_type;
    END IF;

    -- 2.2 约束存在，且覆盖了两个按秒维度
    SELECT pg_get_constraintdef(constraint_row.oid)
    INTO constraint_def
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = 'platform'
      AND table_row.relname = 'package_plan_version'
      AND constraint_row.conname = 'ck_package_version_audio_seconds';

    IF constraint_def IS NULL THEN
        RAISE EXCEPTION 'ck_package_version_audio_seconds_is_missing';
    END IF;
    IF position('VIDEO_SECOND' IN constraint_def) = 0
            OR position('AUDIO_SECOND' IN constraint_def) = 0 THEN
        RAISE EXCEPTION
            'the_constraint_must_allow_both_per_second_dimensions:actual=%',
            constraint_def;
    END IF;

    -- 2.3 约束真的会拦：一个 token 版本不得携带语音秒
    SELECT version.id INTO probe_id
    FROM platform.package_plan_version version
    WHERE version.usage_dimension = 'TOKEN'
    ORDER BY version.id
    LIMIT 1;
    IF probe_id IS NULL THEN
        RAISE EXCEPTION 'no_token_version_exists_to_probe';
    END IF;
    BEGIN
        UPDATE platform.package_plan_version
        SET audio_seconds_quota = 1
        WHERE id = probe_id;
        RAISE EXCEPTION 'a_token_version_was_allowed_to_carry_audio_seconds';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    -- 2.4 约束真的会拦：一个按秒版本不得携带 0 秒（那是"包含语音"的假承诺）
    SELECT version.id INTO probe_id
    FROM platform.package_plan_version version
    WHERE version.usage_dimension = 'VIDEO_SECOND'
    ORDER BY version.id
    LIMIT 1;
    IF probe_id IS NULL THEN
        RAISE EXCEPTION 'no_video_second_version_exists_to_probe';
    END IF;
    BEGIN
        UPDATE platform.package_plan_version
        SET audio_seconds_quota = 0
        WHERE id = probe_id;
        RAISE EXCEPTION 'a_zero_audio_seconds_quota_was_allowed';
    EXCEPTION
        WHEN check_violation THEN NULL;
    END;

    -- 2.5 本步骤是零行为变化：现有版本一个都不许被填上值。
    -- 上面两次探测都在 CHECK 处失败并回滚，因此这里必须是 0 行。
    SELECT count(*) INTO non_null_rows
    FROM platform.package_plan_version version
    WHERE version.audio_seconds_quota IS NOT NULL;
    IF non_null_rows <> 0 THEN
        RAISE EXCEPTION
            'step_one_must_not_change_any_existing_version:filled_rows=%',
            non_null_rows;
    END IF;
END $$;
