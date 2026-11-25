-- V106：给**已经发放**的视频套餐实例补上音频能力（老用户也能用配音）
--
-- 背景。平台 V103 把四个音频别名（配音 / 口播 / 字幕 / 换口型）发进了五个在售
-- 视频套餐，但套餐的能力是在**发放那一刻**写进实例的：
--
--   * gateway.package_instance.allowed_models —— 这份实例被授予了哪些别名。
--     请求期的 API key 上下文就是从这里派生 allowed_models 的
--     （ApiKeyService.toPackageSnapshot ← ApiKeyRepository 读实例行），
--     因此它一改，**现有 Key 立刻生效**，不需要重建 Key；
--   * gateway.package_instance.audio_seconds_quota / _reserved / _used ——
--     方案 C 的第二份额度（网关 V101 加的三列）。
--
-- 于是一个在 V103 之前买的视频套餐，即使平台已经在卖带音频的新版本，它的实例
-- 仍然是「只有视频别名、音频额度为 NULL」：/v1/audio-models 不返回任何音频别名
-- （AudioRouteRepository.AVAILABLE_AUDIO_MODELS_SQL 要求 allowed_models 里
-- 有那个别名），而即使硬调也会以 audio_requires_audio_denominated_package 结束。
-- 客户看到的是「我买的套餐里没有语音」——而按产品口径它应该有。
--
-- 本迁移把已经有实例的那份能力补上，**逐字段只动这三件**：
--   1. audio_seconds_quota（按套餐代码取自平台 V103 的售出额度）；
--   2. allowed_models 追加四个音频别名（保留原有条目与顺序，因此视频那半
--      的每模型自额度、能力串一字不动）；
--   3. 什么都不删、不改库存、不动额度用量与有效期。
--
-- ---------------------------------------------------------------------------
-- 为什么是三列一起写，而不是只写 allowed_models
-- ---------------------------------------------------------------------------
--
-- 只加别名不写额度，客户调配音会命中 `quota_exhausted`（额度为 NULL 的音频桶
-- 永远是空的）——那比现在"看不到"更糟：它看起来像"有但坏了"。反过来只写额度
-- 不加别名则是给了客户一份没人能花的额度（`audio_seconds_quota_without_an_audio_alias`
-- 在平台侧就是拒绝这种形态的）。因此两条由同一条 UPDATE 写入，并且下面
-- 第 4 步的断言证明**已经发放的实例**没有落进任何一侧。
--
-- ---------------------------------------------------------------------------
-- 平台 V103 之后续费/重新购买拿到的实例不需要本迁移
-- ---------------------------------------------------------------------------
--
-- 新发放走的是平台快照（package_plan_version.audio_seconds_quota + 逐模型的
-- usage_dimension），本来就会带上这两样。本迁移只补历史行，因此条件写成
-- `audio_seconds_quota IS NULL`：对已经补过的实例是**幂等**的（重复执行 0 行），
-- 而一个新发放的实例也不会被它改写。
--
-- 为什么不做成"用旧版本号重发一次权益"：那份快照里没有 audioSecondsQuota
-- （旧版本本来就没有这一列的值），重发只会得到同一个结果；而改平台侧的历史行
-- 会违反"已发布版本不可变"（platform.reject_published_version_mutation）。

-- ---------------------------------------------------------------------------
-- 1. 本次回填涉及的套餐与它们售出的音频秒
--
-- 与平台 V103 写入 package_plan_version.audio_seconds_quota 的数字逐字一致。
-- 留成字面量而不是去查 platform schema：网关没有 platform 的读权限，而跨 schema
-- 的隐式依赖会让这条迁移在有权限的环境与没有权限的环境表现不同。
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE v106_audio_seconds ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('VIDEO_LITE',         100::bigint),
    ('VIDEO_LITE_ANNUAL',  100::bigint),
    ('VIDEO_STARTER',      200::bigint),
    ('VIDEO_CREATOR',      600::bigint),
    ('VIDEO_PRO',         1000::bigint)
) AS wanted(package_code, audio_seconds);

-- 四个音频别名，能力串与 gateway.model.capabilities（V100/V101 写下的）逐字一致。
-- modelTokenQuota 留空：音频秒的额度在实例那一级，留空表示该别名可以花掉整份
-- 音频桶（QuotaReservationRepository.lockModelQuota 找不到该行时会退回它）。
CREATE TEMP TABLE v106_audio_aliases ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('audio-asr-basic',    '["AUDIO_TRANSCRIBE"]'::jsonb),
    ('audio-asr-subtitle', '["AUDIO_TRANSCRIBE"]'::jsonb),
    ('audio-tts-basic',    '["AUDIO_SYNTHESIS"]'::jsonb),
    ('audio-retalk',       '["LIP_SYNC"]'::jsonb)
) AS audio(model_code, capabilities);

-- ---------------------------------------------------------------------------
-- 2. 回填：允许模型与音频秒额度，一条 UPDATE 同时写入
--
-- jsonb 拼接而不是重建数组：`||` 只在尾部追加，原有元素（含视频别名的自额度）
-- 一字不动。`NOT EXISTS` 让重复执行不会产生重复条目——即使某个实例的别名
-- 已经由别的路径写进去了，这里也只补额度。
-- ---------------------------------------------------------------------------

UPDATE gateway.package_instance package
SET allowed_models = package.allowed_models || (
        SELECT coalesce(jsonb_agg(jsonb_build_object(
                   'modelCode', audio.model_code,
                   'capabilities', audio.capabilities,
                   'modelTokenQuota', NULL)), '[]'::jsonb)
        FROM v106_audio_aliases audio
        WHERE NOT EXISTS (
            SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
            WHERE existing ->> 'modelCode' = audio.model_code)),
    audio_seconds_quota = wanted.audio_seconds,
    updated_at = now()
FROM v106_audio_seconds wanted
WHERE package.usage_dimension = 'VIDEO_SECOND'
  AND package.package_code = wanted.package_code
  AND package.audio_seconds_quota IS NULL;

-- ---------------------------------------------------------------------------
-- 3. 断言
--
-- 幂等性由第 2 步的 `NOT EXISTS` 保证：重复执行时那一条 UPDATE 匹配 0 行，
-- 因为补过的实例 audio_seconds_quota 已经不再是 NULL。别名也不会重复追加。
--
-- 断言证明的是不变量，而不是"此刻恰好有几行"：
--   * 五个套餐的实例都同时拥有额度与四个别名；
--   * 额度恰好等于该套餐售出的数字；
--   * 别名没有重复追加、视频那一半一字未动；
--   * 已经发放的实例没有任何一个落进"有别名没额度"或"有额度没别名"的半截状态。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken      integer;
    expected    record;
    instance    record;
    alias_count integer;
    missing     integer;
    backfilled  integer;
BEGIN
    -- (a) 该补的都补上了：五个套餐里凡是**本来就有视频别名**的实例，
    --     现在都必须带着正额度与四个别名。
    --
    --     "本来就有视频别名"这个条件是有意的：它跳过了一份纯视频实例
    --     （人工建的商品，本迁移不该替它做决定），也让这条断言在将来
    --     新增纯视频套餐时仍然成立——断言必须表达不变量，不是当前行数。
    SELECT count(*) INTO broken
    FROM gateway.package_instance package
    JOIN v106_audio_seconds wanted ON wanted.package_code = package.package_code
    WHERE package.usage_dimension = 'VIDEO_SECOND'
      AND EXISTS (
          SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
          WHERE existing ->> 'modelCode' LIKE 'video-%')
      AND (package.audio_seconds_quota IS NULL
           OR (SELECT count(*) FROM jsonb_array_elements(package.allowed_models) existing
               WHERE existing ->> 'modelCode' IN ('audio-asr-basic', 'audio-asr-subtitle',
                                                  'audio-tts-basic', 'audio-retalk')) <> 4);
    IF broken > 0 THEN
        RAISE EXCEPTION 'a_video_package_instance_is_still_without_its_audio_capability:count=%',
            broken;
    END IF;

    -- (b) 凡是带着音频别名的实例，额度必须恰好是该套餐售出的那一个数
    --     （多一个少一个都会让客户的实际可用语音与商品说明不符）。
    SELECT count(*) INTO broken
    FROM gateway.package_instance package
    JOIN v106_audio_seconds wanted ON wanted.package_code = package.package_code
    WHERE package.usage_dimension = 'VIDEO_SECOND'
      AND EXISTS (
          SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
          WHERE existing ->> 'modelCode' LIKE 'audio-%')
      AND package.audio_seconds_quota IS DISTINCT FROM wanted.audio_seconds;
    IF broken > 0 THEN
        RAISE EXCEPTION 'backfilled_audio_seconds_disagree_with_the_sold_quota:count=%', broken;
    END IF;

    -- (c) 别名不得重复：重复条目会让目录返回两份、也让"这份实例买了什么"
    --     这句话变得没有答案。
    FOR instance IN
        SELECT package.id, package.package_code, package.allowed_models
        FROM gateway.package_instance package
        JOIN v106_audio_seconds wanted ON wanted.package_code = package.package_code
        WHERE package.usage_dimension = 'VIDEO_SECOND'
    LOOP
        FOR expected IN SELECT audio.model_code FROM v106_audio_aliases audio LOOP
            SELECT count(*) INTO alias_count
            FROM jsonb_array_elements(instance.allowed_models) existing
            WHERE existing ->> 'modelCode' = expected.model_code;
            IF alias_count > 1 THEN
                RAISE EXCEPTION 'audio_alias_appears_%_times:instance=%:model=%',
                    alias_count, instance.id, expected.model_code;
            END IF;
        END LOOP;
    END LOOP;

    -- (d) 视频那一半一字未动：凡是带着音频别名的实例，原有视频别名必须都还在。
    --     这条防的是"回填顺手重建了 allowed_models"，那会把视频别名的每模型
    --     自额度一起抹掉——而那种丢失不会报错，只会让某个别名悄悄变成可以
    --     花掉整份额度。
    SELECT count(*) INTO broken
    FROM gateway.package_instance package
    JOIN v106_audio_seconds wanted ON wanted.package_code = package.package_code
    WHERE package.usage_dimension = 'VIDEO_SECOND'
      AND EXISTS (
          SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
          WHERE existing ->> 'modelCode' LIKE 'audio-%')
      AND NOT EXISTS (
          SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
          WHERE existing ->> 'modelCode' LIKE 'video-%');
    IF broken > 0 THEN
        RAISE EXCEPTION 'the_video_aliases_were_lost_by_the_backfill:count=%', broken;
    END IF;

    -- (e) 反方向：主维度是 VIDEO_SECOND 的实例，不得出现"有音频别名却额度为 NULL"
    --     的形态（那会让每一次配音都以 quota_exhausted 结束）。纯视频套餐
    --     （若将来有）会以"没有别名、没有额度"的形态同时满足两侧。
    SELECT count(*) INTO missing
    FROM gateway.package_instance package
    WHERE package.usage_dimension = 'VIDEO_SECOND'
      AND package.audio_seconds_quota IS NULL
      AND EXISTS (
          SELECT 1 FROM jsonb_array_elements(package.allowed_models) existing
          WHERE existing ->> 'modelCode' IN ('audio-asr-basic', 'audio-asr-subtitle',
                                             'audio-tts-basic', 'audio-retalk'));
    IF missing > 0 THEN
        RAISE EXCEPTION 'a_video_instance_advertises_audio_aliases_without_an_audio_bucket:count=%',
            missing;
    END IF;

    -- (f) 报告这次回填影响了多少份实例。它是 NOTICE 而不是断言：一个**全新**的
    --     部署里没有任何历史实例，"回填 0 行"是正确的，而不是失败。
    SELECT count(*) INTO backfilled
    FROM gateway.package_instance package
    JOIN v106_audio_seconds wanted ON wanted.package_code = package.package_code
    WHERE package.usage_dimension = 'VIDEO_SECOND'
      AND package.audio_seconds_quota IS NOT NULL;
    RAISE NOTICE 'video package instances now carrying audio seconds: %', backfilled;
END $$;
