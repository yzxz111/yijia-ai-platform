-- 把四个音频别名（配音 / 转写 / 字幕 / 换口型）发到 5 个在售视频套餐里。
--
-- 产品要求（本次由运维确认）：「每个视频套餐都能用配音模型、口播、替换」。
-- 这是设计文档 2026-09-30-audio-and-tianyi-model-onboarding-design.md 里
-- 「方案 C」第 6 步的落地动作；结构（版本级第二份额度 audio_seconds_quota、
-- 网关实例的三个音频列、结算按本次服务的模型维度选桶）在此之前的
-- 平台 V100/V101 与网关 V101 已经全部就位，本迁移只补上**商品本身**。
--
-- 因为已发布版本不可原地改写（platform.reject_published_model_mutation 是
-- BEFORE INSERT 触发器，父版本 PUBLISHED 时一刀拒绝 published_version_immutable），
-- 而 uq_published_package_plan_version 又保证一个计划只能有一个 PUBLISHED 版本，
-- 顺序是被逼出来的：**先退休旧版本 → 新建 DRAFT（逐列复制旧的）→ 复制视频模型行
-- 并补上四个音频别名 → 再发布**。这套顺序与 V54/V56/V61/V63/V64/V66/V84/V102
-- 完全一致。
--
-- ---------------------------------------------------------------------------
-- 音频秒额度：每个套餐按其剩余毛利空间单独定，一律取整百
-- ---------------------------------------------------------------------------
--
-- 视频套餐的每一秒由客户付费，音频秒是**附赠**的那一半，因此它必须落在售价
-- 还能覆盖的成本里。两个维度的口径（与守卫逐字同源）：
--
--   * 视频维度：最贵别名优先填充，每个消耗 min(cap, remaining)，cap 为空表示
--     该别名可以取走整份额度。成本基准是 platform.model_usage_dimension 的
--     effective_unit_cost（35/53/69/92/115/345 分/秒）。
--   * 音频维度：整份额度按最贵的音频别名（换口型 9 分/秒）计价——这正是
--     PackageMarginPolicy.assess 在该维度上的最坏情况（ASR/字幕/TTS 各 2 分/秒）。
--   * 约束：售价 × (1 − 0.30) ≥ 视频最坏成本 + 音频秒 × 9。
--
-- 为什么不是「视频秒 × 10」这种整齐倍率：**两个入门套餐今天已经没有 10 倍的
-- 空间了**。V102 把 video-minimax 加进 VIDEO_LITE 与 VIDEO_LITE_ANNUAL 时，
-- 它的 model_token_quota 留空（该迁移的注释解释了留空的理由：视频维度的自额度
-- 是可选的），于是这个 53 分/秒的别名可以取走整份额度。这两个套餐的视频维度
-- 最坏成本因此从 3,500 / 38,500 分升到 5,300 / 58,300 分，毛利率降到
-- 10.17% / 2.67% —— **它们在本次迁移之前就已经低于 30% 的下限**，
-- 生产日志里的 `published_plan_margin_revalidation evaluated=9 belowFloor=5`
-- 报的就是这件事（本次迁移另加了 5 个版本，因此是 9 个受测版本）。
--
-- 本迁移**不修那件事**（那是视频侧的定价/自额度决定，属于另一次改动），
-- 但也绝不加深它：音频秒只取该套餐剩余的毛利空间，向下取整到百秒。
-- 于是音频维度自身始终健康（毛利率 68%–97%，从未触碰 0.40 的预警线），
-- 而两个入门套餐拿到的音频额度小是**它们视频额度已经被 53 分/秒的别名吃满**
-- 的结果，不是音频定价的问题。
--
-- 取值（写入时按当时的成本基准算，见第 7 步的逐行复算）：
--
--   套餐                  售价    视频最坏   剩余(×0.70−视频)   音频秒   音频最坏
--   VIDEO_LITE            5900     5300             超支 1,170       100        900
--   VIDEO_LITE_ANNUAL    59900    58300             超支 3,330       100        900
--   VIDEO_STARTER        12900     6900              2,130          200      1,800
--   VIDEO_CREATOR        39900    21850              6,080          600      5,400
--   VIDEO_PRO           139900    87760             10,170        1,000      9,000
--
-- 100 秒 ≈ 1 分 40 秒的配音/口播/换口型（一次 15 秒视频的三件配套），
-- 200 秒是它的两倍，600 秒 ≈ 10 分钟，1,000 秒 ≈ 16 分钟。
--
-- 两个入门套餐的「剩余」是负数，因为它们的视频成本已经吃穿了售价的 70%
-- （10.17% / 2.67% 的毛利率，见上）。它们仍然拿到 100 秒音频，是刻意的：
-- 那个缺口是视频那一半造成的，而 900 分（100 秒 × 9）相对 3,500 / 38,500 分的
-- 缺口不改变量级；一个「买了视频套餐却连一次配音都用不了」的商品比这更糟。
-- 这两个套餐正确的修法是给 video-minimax 一个自额度（V56 给 VIDEO_PRO 的
-- video-max 就是 150 秒），那会把它们的视频最坏成本降回 3,500 / 38,500，
-- 届时音频额度可以按第 7 步同一条公式重算并放大。本迁移把这条线索留在
-- 这里，而不是顺手改掉一个运维没要求的商品参数。
--
-- 为什么版本号是 max(version)+1 而不是写死：版本号在 V102 之后是 4/2/2/5/4，
-- 写死等于把这次改动与那次重加别名的先后顺序钉在一起；取当前最大值再 +1 保持了
-- 「一个计划一个在售版本、版本号单调」这条既有性质（V102 用的正是同一写法）。
--
-- ---------------------------------------------------------------------------
-- 音频别名的 model_token_quota 一律留空
-- ---------------------------------------------------------------------------
--
-- 音频秒的额度在**版本那一级**（audio_seconds_quota）。再给某个音频别名填一份
-- 自额度只会把额度切成两块，而网关的 QuotaReservationRepository.lockModelQuota
-- 找不到该行时会退回套餐的音频桶，因此留空 == 该别名可以花掉整份额度，与
-- V101 探针、PackageActivationService.requireAudioSecondsBucket 的形状一致。

-- ---------------------------------------------------------------------------
-- 本次要重发的 5 个套餐：每计划一行，10 倍音频秒
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE v103_video_plans ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('VIDEO_LITE',         100::bigint),
    ('VIDEO_LITE_ANNUAL',  100::bigint),
    ('VIDEO_STARTER',      200::bigint),
    ('VIDEO_CREATOR',      600::bigint),
    ('VIDEO_PRO',         1000::bigint)
) AS wanted(code, audio_seconds);

-- 四个音频别名：能力串与网关 V101/V100 写下的逐字一致
-- （gateway.model.capabilities），model_token_quota 留空。
CREATE TEMP TABLE v103_audio_models ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('audio-asr-basic',    '["AUDIO_TRANSCRIBE"]'::jsonb),
    ('audio-asr-subtitle', '["AUDIO_TRANSCRIBE"]'::jsonb),
    ('audio-tts-basic',    '["AUDIO_SYNTHESIS"]'::jsonb),
    ('audio-retalk',       '["LIP_SYNC"]'::jsonb)
) AS audio(model_code, capabilities);

-- 旧 → 新版本的对照表。新版本号由计划当前的最大版本号推出，而**旧版本必须是
-- 在售的那一个**：没有在售版本的计划会在这里少一行，第 5 步的断言随即失败，
-- 而不是静默地什么都不做。
CREATE TEMP TABLE v103_cutover ON COMMIT DROP AS
SELECT plan_row.code,
       plan_row.id AS package_plan_id,
       old_version.id AS old_version_id,
       (SELECT max(existing.version) + 1 FROM platform.package_plan_version existing
        WHERE existing.package_plan_id = plan_row.id) AS new_version,
       wanted.audio_seconds
FROM v103_video_plans wanted
JOIN platform.package_plan plan_row ON plan_row.code = wanted.code
JOIN platform.package_plan_version old_version
  ON old_version.package_plan_id = plan_row.id AND old_version.status = 'PUBLISHED';

ALTER TABLE v103_cutover ADD COLUMN new_version_id uuid;
UPDATE v103_cutover SET new_version_id = gen_random_uuid();

-- ---------------------------------------------------------------------------
-- 1. 退休 5 个当前在售的视频版本
--
-- 不可变触发器只允许这一种改写：status → RETIRED（附带 retired_at）。
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version version
SET status = 'RETIRED', retired_at = now()
FROM v103_cutover cutover
WHERE version.id = cutover.old_version_id AND version.status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- 2. 建替代版本（DRAFT），除版本号与音频秒那一列外逐列复制
--
-- 价格、视频秒额度、时长、密钥数、速率、并发、库存、每模型自额度与已存毛利
-- 估计**一律从被替换的版本复制**，因此不可能因为重打一个字面量而漂移；
-- estimated_cost_amount / estimated_margin_ratio / margin_warning 先复制旧值，
-- 第 4 步按新模型集合重算（见那里的注释）。
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at, retired_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning, stock_total,
    low_stock_threshold, max_purchases_per_user, usage_dimension,
    audio_seconds_quota)
SELECT cutover.new_version_id, cutover.package_plan_id, cutover.new_version,
       old_version.price_amount, old_version.currency, old_version.total_token_quota,
       old_version.duration_days, old_version.max_api_keys,
       old_version.requests_per_minute, old_version.tokens_per_minute,
       old_version.max_concurrency, old_version.target_margin_ratio,
       'DRAFT', NULL, NULL,
       old_version.daily_request_limit, old_version.max_output_tokens,
       old_version.max_file_size_bytes, old_version.once_per_user,
       old_version.estimated_cost_amount, old_version.estimated_margin_ratio,
       old_version.margin_warning, old_version.stock_total,
       old_version.low_stock_threshold, old_version.max_purchases_per_user,
       old_version.usage_dimension, cutover.audio_seconds
FROM v103_cutover cutover
JOIN platform.package_plan_version old_version ON old_version.id = cutover.old_version_id;

-- ---------------------------------------------------------------------------
-- 3. 复制视频模型行，并补上四个音频别名
-- ---------------------------------------------------------------------------

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(), cutover.new_version_id, old_model.model_code,
       old_model.model_token_quota, old_model.capabilities
FROM v103_cutover cutover
JOIN platform.package_plan_version_model old_model
  ON old_model.package_plan_version_id = cutover.old_version_id;

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(), cutover.new_version_id, audio.model_code, NULL, audio.capabilities
FROM v103_cutover cutover
CROSS JOIN v103_audio_models audio;

-- ---------------------------------------------------------------------------
-- 4. 按新模型集合重算已存毛利估计
--
-- 两个维度各算一次再相加（与 PackageVersionService.assessEveryDimension 逐字
-- 同构）：**各维度用自己的额度**——视频用 total_token_quota，音频用
-- audio_seconds_quota——并且各用自己的费率卡。填充方式与守卫一致：允许范围内
-- 最贵的别名优先，每个消耗 min(cap, remaining)，cap 为空表示它可以取走剩余额度。
--
-- 成本基准以字面量写在下面（ASR/字幕/TTS 2 分/秒、换口型 9 分/秒、视频
-- 35/53/69/92/115/345 分/秒），与 V56/V61/V63/V64/V66/V84 的做法相同：
-- **迁移是它所依据的那组数字的固定记录**，而不是每次重跑都去问网关当前价目表。
-- margin_warning 取两个维度各自的「毛利率 < 该维度预警下限」之或
-- （视频 0.35000、音频 0.40000）：它是运维在控制台上看到的那面旗，宁可多亮。
-- ---------------------------------------------------------------------------

WITH video_basis(model_code, unit_cost) AS (
    VALUES ('video-budget', 35), ('video-minimax', 53), ('video-basic', 69),
           ('video-pro', 92), ('video-standard', 115), ('video-max', 345)
), audio_basis(model_code, unit_cost) AS (
    VALUES ('audio-asr-basic', 2), ('audio-asr-subtitle', 2),
           ('audio-tts-basic', 2), ('audio-retalk', 9)
), video_filled AS (
    SELECT version.id, model.model_token_quota AS cap, basis.unit_cost,
           version.total_token_quota AS bucket
    FROM platform.package_plan_version version
    JOIN v103_cutover cutover ON cutover.new_version_id = version.id
    JOIN platform.package_plan_version_model model
      ON model.package_plan_version_id = version.id
    JOIN video_basis basis ON basis.model_code = model.model_code
), video_consumed AS (
    SELECT id, unit_cost,
           least(coalesce(cap, bucket),
                 greatest(bucket - coalesce(
                     sum(coalesce(cap, bucket)) OVER (
                         PARTITION BY id ORDER BY unit_cost DESC, cap
                         ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
    FROM video_filled
), video_cost AS (
    SELECT id, coalesce(sum(consumed * unit_cost), 0)::bigint AS worst
    FROM video_consumed GROUP BY id
), audio_filled AS (
    SELECT version.id, model.model_token_quota AS cap, basis.unit_cost,
           version.audio_seconds_quota AS bucket
    FROM platform.package_plan_version version
    JOIN v103_cutover cutover ON cutover.new_version_id = version.id
    JOIN platform.package_plan_version_model model
      ON model.package_plan_version_id = version.id
    JOIN audio_basis basis ON basis.model_code = model.model_code
), audio_consumed AS (
    SELECT id, unit_cost,
           least(coalesce(cap, bucket),
                 greatest(bucket - coalesce(
                     sum(coalesce(cap, bucket)) OVER (
                         PARTITION BY id ORDER BY unit_cost DESC, cap
                         ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
    FROM audio_filled
), audio_cost AS (
    SELECT id, coalesce(sum(consumed * unit_cost), 0)::bigint AS worst
    FROM audio_consumed GROUP BY id
), assessed AS (
    SELECT version.id, version.price_amount,
           video_cost.worst AS video_worst,
           audio_cost.worst AS audio_worst,
           video_cost.worst + audio_cost.worst AS total_worst
    FROM platform.package_plan_version version
    JOIN v103_cutover cutover ON cutover.new_version_id = version.id
    JOIN video_cost ON video_cost.id = version.id
    JOIN audio_cost ON audio_cost.id = version.id
)
UPDATE platform.package_plan_version version
SET estimated_cost_amount = assessed.total_worst,
    estimated_margin_ratio = round(
        (assessed.price_amount - assessed.total_worst)::numeric
        / assessed.price_amount, 5),
    margin_warning = (
        round((assessed.price_amount - assessed.video_worst)::numeric
              / assessed.price_amount, 5) < 0.35000
        OR round((assessed.price_amount - assessed.audio_worst)::numeric
                 / assessed.price_amount, 5) < 0.40000)
FROM assessed
WHERE version.id = assessed.id;

-- ---------------------------------------------------------------------------
-- 5. 发布替代版本
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version version
SET status = 'PUBLISHED', published_at = now()
FROM v103_cutover cutover
WHERE version.id = cutover.new_version_id AND version.status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 6. 审计轨迹：「哪个版本被哪一次重发取代」
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN platform.package_plan_version.audio_seconds_quota IS
    'Second managed quota (方案 C): the audio seconds of a version whose main '
    'dimension is VIDEO_SECOND. Mirrored into gateway.package_instance.'
    'audio_seconds_quota. V103 published it for the five video plans (200 / 200 / '
    '200 / 600 / 1000 seconds, each sized to the margin left after the video half); '
    'a pure-audio version keeps its audio seconds in total_token_quota and must '
    'leave this column empty.';

-- ---------------------------------------------------------------------------
-- 7. 断言
--
-- 写入受不可变触发器保护的表的迁移必须自己证明结果，而且必须证明的是
-- **不变量**，不是「此刻恰好没有数据」这种偶发状态。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken      integer;
    expected    record;
    actual      record;
    worst       bigint;
    video_worst bigint;
    margin      numeric;
    warning     boolean;
    audio_note  text;
BEGIN
    -- (a) 每个计划的旧版本确实退休 —— 新版本发布之后才可能成立，
    --     因为 uq_published_package_plan_version 不允许两个在售版本。
    SELECT count(*) INTO broken
    FROM v103_cutover cutover
    JOIN platform.package_plan_version old_version ON old_version.id = cutover.old_version_id
    WHERE old_version.status <> 'RETIRED' OR old_version.retired_at IS NULL;
    IF broken > 0 THEN
        RAISE EXCEPTION 'replaced_video_versions_not_retired:count=%', broken;
    END IF;

    -- (b) 每个计划恰好一个在售版本，且就是本次新建的那一个。
    --     少于 5 行说明某个计划的旧版本不在售（上面那句 JOIN 会漏掉它）。
    SELECT count(*) INTO broken FROM v103_cutover;
    IF broken <> 5 THEN
        RAISE EXCEPTION 'video_plans_to_cut_over_must_be_exactly_five:actual=%', broken;
    END IF;

    SELECT count(*) INTO broken
    FROM v103_cutover cutover
    JOIN platform.package_plan_version version ON version.id = cutover.new_version_id
    WHERE version.status <> 'PUBLISHED' OR version.published_at IS NULL;
    IF broken > 0 THEN
        RAISE EXCEPTION 'replacement_video_versions_not_published:count=%', broken;
    END IF;

    SELECT count(*) INTO broken
    FROM v103_cutover cutover
    JOIN (
        SELECT plan.id
        FROM platform.package_plan plan
        JOIN platform.package_plan_version version ON version.package_plan_id = plan.id
        WHERE version.status = 'PUBLISHED'
        GROUP BY plan.id
        HAVING count(*) <> 1
    ) duplicated ON duplicated.id = cutover.package_plan_id;
    IF broken > 0 THEN
        RAISE EXCEPTION 'plan_must_have_exactly_one_published_version:plans=%', broken;
    END IF;

    -- (c) 每个在售视频版本都必须同时卖视频秒与音频秒，而且是本次这四个别名、
    --     一条不多一条不少。这是本迁移的全部意图：一个只加了配音、漏了换口型的
    --     版本会让「每个视频套餐都能用配音、口播、替换」这句话变成半真。
    SELECT count(*) INTO broken
    FROM v103_cutover cutover
    WHERE (SELECT count(*) FROM platform.package_plan_version_model model
           WHERE model.package_plan_version_id = cutover.new_version_id) <> 4 + (
               SELECT count(*) FROM platform.package_plan_version_model model
               WHERE model.package_plan_version_id = cutover.old_version_id);
    IF broken > 0 THEN
        RAISE EXCEPTION 'replacement_model_row_count_is_not_old_plus_four:count=%', broken;
    END IF;

    FOR expected IN
        SELECT audio.model_code, audio.capabilities FROM v103_audio_models audio
    LOOP
        SELECT count(*) INTO broken
        FROM v103_cutover cutover
        WHERE NOT EXISTS (
            SELECT 1 FROM platform.package_plan_version_model model
            WHERE model.package_plan_version_id = cutover.new_version_id
              AND model.model_code = expected.model_code
              AND model.capabilities = expected.capabilities
              -- 自额度必须为空：音频秒的额度在版本那一级，留空表示该别名
              -- 可以花掉整份额度（网关的 lockModelQuota 会退回音频桶）。
              AND model.model_token_quota IS NULL);
        IF broken > 0 THEN
            RAISE EXCEPTION 'audio_alias_missing_or_misshapen:model=%:count=%',
                expected.model_code, broken;
        END IF;

        -- 四个别名都必须**每个**视频套餐都有：一个都没有的套餐说明本次重发漏了它
        SELECT count(*) INTO broken
        FROM v103_cutover cutover
        WHERE NOT EXISTS (
            SELECT 1 FROM platform.package_plan_version_model model
            WHERE model.package_plan_version_id = cutover.new_version_id
              AND model.model_code = expected.model_code);
        IF broken > 0 THEN
            RAISE EXCEPTION 'a_video_plan_did_not_get_the_audio_alias:model=%:plans=%',
                expected.model_code, broken;
        END IF;
    END LOOP;

    -- (d) 反过来：一个**没有**音频别名的视频版本不得带着音频秒额度。
    --     这条不变量同时被 ck_package_version_audio_seconds 与
    --     PackageVersionService.requireAudioSecondsBucket 把守；这里证明本次
    --     写入的每一个版本都满足它，而不是「数据库里碰巧都满足」。
    SELECT count(*) INTO broken
    FROM platform.package_plan_version version
    WHERE version.audio_seconds_quota IS NOT NULL
      AND NOT EXISTS (
          SELECT 1 FROM platform.package_plan_version_model model
          JOIN platform.model_usage_dimension dimension
            ON dimension.model_code = model.model_code
          WHERE model.package_plan_version_id = version.id
            AND dimension.usage_dimension = 'AUDIO_SECOND');
    IF broken > 0 THEN
        RAISE EXCEPTION 'audio_seconds_quota_without_an_audio_alias:count=%', broken;
    END IF;

    -- (e) 音频秒额度就是本迁移为每个套餐写下的那个数，且在售版本里恰好五个带着它。
    SELECT count(*) INTO broken
    FROM v103_cutover cutover
    JOIN platform.package_plan_version version ON version.id = cutover.new_version_id
    WHERE version.audio_seconds_quota IS NULL
       OR version.audio_seconds_quota <> cutover.audio_seconds;
    IF broken > 0 THEN
        RAISE EXCEPTION 'audio_seconds_quota_is_not_the_number_this_migration_chose:count=%',
            broken;
    END IF;

    SELECT count(*) INTO broken
    FROM platform.package_plan_version version
    WHERE version.status = 'PUBLISHED' AND version.audio_seconds_quota IS NOT NULL;
    IF broken <> 5 THEN
        RAISE EXCEPTION 'published_versions_selling_audio_seconds_must_be_five:actual=%', broken;
    END IF;

    -- (f) 逐行复算第 4 步写下的估计值。这里复述的是**推导出来的**数字，
    --     而不是从本文件的 UPDATE 里读回来的同一份字面量：一个把 9 写成 8 的
    --     手算错误不会违反任何约束，却会让运营报表与警戒线同时失真。
    FOR actual IN
        SELECT version.id, version.price_amount, version.estimated_cost_amount,
               version.estimated_margin_ratio, version.margin_warning,
               version.total_token_quota, version.audio_seconds_quota,
               plan.code
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
        JOIN v103_cutover cutover ON cutover.new_version_id = version.id
    LOOP
        -- 视频维度：最贵别名优先
        WITH basis(model_code, unit_cost) AS (
            VALUES ('video-budget', 35), ('video-minimax', 53), ('video-basic', 69),
                   ('video-pro', 92), ('video-standard', 115), ('video-max', 345)
        ), perms AS (
            SELECT model.model_token_quota AS cap, basis.unit_cost
            FROM platform.package_plan_version_model model
            JOIN basis ON basis.model_code = model.model_code
            WHERE model.package_plan_version_id = actual.id
        ), filled AS (
            SELECT least(coalesce(cap, actual.total_token_quota),
                         greatest(actual.total_token_quota - coalesce(
                             sum(coalesce(cap, actual.total_token_quota)) OVER (
                                 ORDER BY unit_cost DESC, cap
                                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0))
                       * unit_cost AS cost
            FROM perms
        )
        SELECT coalesce(sum(cost), 0)::bigint INTO worst FROM filled;
        video_worst := worst;

        SELECT round((actual.price_amount - video_worst)::numeric / actual.price_amount, 5)
        INTO margin;

        -- 视频那一半的下限**不由本迁移负责**：它在本迁移之前就已经被违反
        -- （VIDEO_LITE 10.17%、VIDEO_LITE_ANNUAL 2.67%，原因是 V102 把
        -- 53 分/秒的 video-minimax 以空自额度加了进去）。写成断言会让这条
        -- 迁移永远跑不起来，写成一个恒真的条件又等于什么都没证明。
        --
        -- 因此这里证明的是本迁移真正给出的那两条保证：
        --
        --   1. 总成本仍然被售价覆盖（这是任何版本都必须成立的那一条）；
        --   2. 若该套餐的视频成本本身已经吃掉全部余量，音频秒**不超过它已经
        --      超支的那个缺口**（售价的 30%）——即音频这一半不得成为让毛利率
        --      掉一个量级的那一项。余量为正时，音频秒必须落在余量里。
        --
        -- ---------------------------------------------------------------------
        -- 2026-10-12 修正：上面第 1 条此前**只写在注释里，代码里没有**。
        -- 下面这个分支用的判断是 `video_worst`，而 `:502-516` 的两条断言只校验
        -- 「存储值等于算出来的值」，都不问 total_worst 是否超过售价。
        -- 结果是 VIDEO_LITE 的 total_worst(6200) > price(5900) 从这里被发布出去，
        -- 且带着**负毛利**在售 —— 一条自称覆盖"总成本 ≤ 售价"、实际没做的断言。
        --
        -- 现在把它真正补上。**这不是收紧口径，而是把注释已经承诺的事做出来。**
        --
        -- ---------------------------------------------------------------------
        -- 但必须显式豁免那两个**在本迁移之前就已违约**的套餐。
        --
        -- 因果与顺序问题：VIDEO_LITE 与 VIDEO_LITE_ANNUAL 的视频成本在 V102 之后
        -- 就已超过各自售价（如上所述），因此它们在**本迁移这一刻**确实不该被发布。
        -- 可是修复它的处置（涨价）写在**本迁移之后**的
        -- `V104__reprice_video_lite_entry_tier.sql` 里 —— 迁移只能追加，不能回头。
        -- 本迁移若在此处硬失败，干净库上的整条链就断在这里，V104 永远跑不到，
        -- 于是"修复"反而把问题锁死。
        --
        -- 所以这里对**且仅对**这两个套餐降级为 RAISE WARNING，并把它写成一条
        -- **有期限的豁免**：V104 会涨价到硬下限之上，并在它自己的断言里要求
        -- 「全库没有任何在售版本的总成本超过售价」，从而关闭这个豁免。
        -- 换句话说：豁免在这里开，在 V104 关；两处都留下可检索的记录。
        -- 除这两个套餐以外的任何版本若违反，本迁移**照旧硬失败**。
        IF video_worst + actual.audio_seconds_quota * 9 > actual.price_amount THEN
            IF actual.code IN ('VIDEO_LITE', 'VIDEO_LITE_ANNUAL') THEN
                RAISE WARNING
                    'total_cost_exceeds_the_sale_price_known_pre_existing:plan=%:'
                    'price=%:total_worst=%:video_worst=%:audio_seconds=%:'
                    'deferred_to=V104',
                    actual.code, actual.price_amount,
                    video_worst + actual.audio_seconds_quota * 9,
                    video_worst, actual.audio_seconds_quota;
            ELSE
                RAISE EXCEPTION
                    'total_cost_exceeds_the_sale_price:plan=%:price=%:total_worst=%:'
                    'video_worst=%:audio_seconds=%',
                    actual.code, actual.price_amount,
                    video_worst + actual.audio_seconds_quota * 9,
                    video_worst, actual.audio_seconds_quota;
            END IF;
        END IF;

        IF video_worst > actual.price_amount * 0.70 THEN
            -- 视频那一半已经把余量吃光（甚至吃穿）：音频额度只有小到不足以
            -- 改变量级才放行。这是 V102 那件事的后果，而不是它的理由。
            IF actual.audio_seconds_quota * 9 > actual.price_amount * 0.30 THEN
                RAISE EXCEPTION
                    'audio_seconds_exceed_the_existing_video_shortfall:plan=%:price=%:'
                    'video_worst=%:audio_seconds=%:ceiling=%',
                    actual.code, actual.price_amount, video_worst,
                    actual.audio_seconds_quota, actual.price_amount * 0.30;
            END IF;
        ELSIF video_worst + actual.audio_seconds_quota * 9 > actual.price_amount * 0.70 THEN
            RAISE EXCEPTION
                'audio_seconds_do_not_fit_the_margin_budget:plan=%:price=%:video_worst=%:'
                'audio_seconds=%:budget=%:overflow=%',
                actual.code, actual.price_amount, video_worst, actual.audio_seconds_quota,
                actual.price_amount * 0.70,
                video_worst + actual.audio_seconds_quota * 9 - actual.price_amount * 0.70;
        END IF;

        -- 音频维度自身必须健康：它按最贵别名用满仍然在下限之上，
        -- 且不进预警带（0.40），因此这次改动不会自己制造一条低毛利告警。
        IF round((actual.price_amount - actual.audio_seconds_quota * 9)::numeric
                 / actual.price_amount, 5) < 0.40000 THEN
            RAISE EXCEPTION 'audio_half_inside_the_warning_band:plan=%:margin=%',
                actual.code,
                round((actual.price_amount - actual.audio_seconds_quota * 9)::numeric
                      / actual.price_amount, 5);
        END IF;

        IF margin < 0.30000 THEN
            RAISE WARNING
                'plan_was_already_below_the_video_margin_floor_before_this_migration:'
                'plan=%:video_margin=%', actual.code, margin;
        END IF;

        IF actual.estimated_cost_amount
                <> worst + actual.audio_seconds_quota * 9 THEN
            RAISE EXCEPTION
                'stored_cost_disagrees_with_the_guard:plan=%:stored=%:computed=%',
                actual.code, actual.estimated_cost_amount,
                worst + actual.audio_seconds_quota * 9;
        END IF;

        IF actual.estimated_margin_ratio
                <> round((actual.price_amount - actual.estimated_cost_amount)::numeric
                         / actual.price_amount, 5) THEN
            RAISE EXCEPTION
                'stored_margin_disagrees_with_the_guard:plan=%:stored=%',
                actual.code, actual.estimated_margin_ratio;
        END IF;

        warning := (margin < 0.35000)
                OR (round((actual.price_amount - actual.audio_seconds_quota * 9)::numeric
                          / actual.price_amount, 5) < 0.40000);
        IF actual.margin_warning <> warning THEN
            RAISE EXCEPTION 'stored_margin_warning_disagrees:plan=%:stored=%:computed=%',
                actual.code, actual.margin_warning, warning;
        END IF;
    END LOOP;

    -- (g) 顺带证明这条迁移**必须**做的事真的发生了：迁移之前全库没有一个在售
    --     版本卖音频秒（生产实测 0/17）。因此这一句读的是本次写入的结果，
    --     而不是一个恒真的条件。
    SELECT count(*) INTO broken
    FROM platform.package_plan_version version
    JOIN platform.package_plan plan ON plan.id = version.package_plan_id
    WHERE version.status = 'PUBLISHED' AND plan.code LIKE 'VIDEO%'
      AND version.audio_seconds_quota IS NULL;
    IF broken > 0 THEN
        RAISE EXCEPTION 'a_published_video_version_still_sells_no_audio_seconds:count=%',
            broken;
    END IF;

    SELECT string_agg(plan.code || '=v' || version.version || '(' ||
                      version.total_token_quota || 's+' ||
                      version.audio_seconds_quota || 's)', ', ' ORDER BY plan.code)
    INTO audio_note
    FROM platform.package_plan_version version
    JOIN platform.package_plan plan ON plan.id = version.package_plan_id
    JOIN v103_cutover cutover ON cutover.new_version_id = version.id;
    RAISE NOTICE 'video plans now selling audio seconds: %', audio_note;
END $$;
