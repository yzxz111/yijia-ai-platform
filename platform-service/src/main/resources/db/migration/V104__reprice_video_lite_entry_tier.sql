-- 给两个入门视频套餐涨价：修掉「加了贵一倍的别名却没改价」造成的负毛利
--
-- WHY. V102 把 video-minimax（53 分/秒，是这两个套餐里最贵的别名，且**没有
-- model_token_quota**）加进了在售的 VIDEO_LITE 与 VIDEO_LITE_ANNUAL。它因此可以
-- 吃满整份视频秒额度，两个套餐的视频维度最坏成本从 3,500 / 38,500 分升到
-- 5,300 / 58,300 分，而**售价一分未动**。V103 又各加了 100 秒音频（900 分），
-- 于是 VIDEO_LITE 的总成本 6,200 分 > 售价 5,900 分 —— 每卖一份亏 300 分。
--
-- V103 的注释把这件事写清楚了，但它的断言**只检查音频装不装得进余量**，
-- 没有任何一条问「总成本是否超过售价」。本次之前已给 V103 补上那条硬拦
-- （total_cost_exceeds_the_sale_price），于是这条迁移成为解除该拦截的处置。
--
-- 决策：**涨价，不砍别名、不动任何额度与模型**（产品侧 2026-10-12 拍板）。
-- 因此本迁移只改一列 price_amount，其余逐字复制。
--
--   plan                 售价            总成本    毛利
--   VIDEO_LITE           5,900  → 8,800  6,200    -5.085% → +29.545%
--   VIDEO_LITE_ANNUAL   59,900 → 88,000 59,200    +1.169% → +32.727%
--
-- 两个都越过 25% 的硬下限（V58/V54 的口径）。仍在 35% 的预警线之下，
-- 因此 margin_warning 保持 true —— 那面旗本来就该亮，这里不掩盖它。
--
-- NEVER LOWER, NEVER GUESS. 价格用 GREATEST 防御性抬高：若某个目录已经被运维
-- 手工涨价到超过本文件的值，本迁移不会把价格降回去。两个套餐的模型集合、
-- 视频秒额度、音频秒额度、时长、库存等一律从**当前在售版本**复制，
-- 不可能因为重打一个字面量而漂移。
--
-- STATE-DRIVEN, NOT ID-DRIVEN. 与 V89/V102/V103 同一口径：本迁移读「当前
-- PUBLISHED 的那一行」而不是指名某个 id，因为目录可能是从云快照恢复的
-- （那种目录里版本号与 id 都与本地链不同）。

-- ---------------------------------------------------------------------------
-- 1. 目标：两个套餐的新售价
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE v104_repricing ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('VIDEO_LITE'::text,         8800::bigint),
    ('VIDEO_LITE_ANNUAL'::text, 88000::bigint)
) AS wanted(plan_code, new_price);

CREATE TEMP TABLE v104_cutover ON COMMIT DROP AS
SELECT wanted.plan_code,
       plan_row.id AS package_plan_id,
       old_version.id AS old_version_id,
       old_version.price_amount AS old_price,
       GREATEST(old_version.price_amount, wanted.new_price) AS new_price,
       (SELECT max(existing.version) + 1 FROM platform.package_plan_version existing
        WHERE existing.package_plan_id = plan_row.id) AS new_version
FROM v104_repricing wanted
JOIN platform.package_plan plan_row ON plan_row.code = wanted.plan_code
JOIN platform.package_plan_version old_version
  ON old_version.package_plan_id = plan_row.id AND old_version.status = 'PUBLISHED';

ALTER TABLE v104_cutover ADD COLUMN new_version_id uuid;
UPDATE v104_cutover SET new_version_id = gen_random_uuid();

-- 两个套餐都必须有在售版本，否则说明目录不是本文件预期的形状 —— 宁可停下来。
DO $$
DECLARE
    found integer;
BEGIN
    SELECT count(*) INTO found FROM v104_cutover;
    IF found <> 2 THEN
        RAISE EXCEPTION 'video_lite_plans_missing_a_published_version:count=%', found;
    END IF;
    -- 防御：本次只允许涨价。若某一行算出来不是涨价，说明前提变了。
    IF EXISTS (SELECT 1 FROM v104_cutover WHERE new_price < old_price) THEN
        RAISE EXCEPTION 'video_lite_repricing_would_lower_a_price';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. 退休当前在售版本
--
-- 不可变触发器只允许这一种改写：status → RETIRED（附带 retired_at）。
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version version
SET status = 'RETIRED', retired_at = now()
FROM v104_cutover cutover
WHERE version.id = cutover.old_version_id AND version.status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- 3. 建替代版本（DRAFT）
--
-- 除 price_amount 与版本号外**逐列复制**。毛利估计先复制旧值，
-- 第 5 步按新价重算。
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
       cutover.new_price, old_version.currency, old_version.total_token_quota,
       old_version.duration_days, old_version.max_api_keys, old_version.requests_per_minute,
       old_version.tokens_per_minute, old_version.max_concurrency,
       old_version.target_margin_ratio, 'DRAFT', NULL, NULL,
       old_version.daily_request_limit, old_version.max_output_tokens,
       old_version.max_file_size_bytes, old_version.once_per_user,
       old_version.estimated_cost_amount, old_version.estimated_margin_ratio,
       old_version.margin_warning, old_version.stock_total,
       old_version.low_stock_threshold, old_version.max_purchases_per_user,
       old_version.usage_dimension, old_version.audio_seconds_quota
FROM v104_cutover cutover
JOIN platform.package_plan_version old_version ON old_version.id = cutover.old_version_id;

-- 模型权限逐字复制。必须在仍是 DRAFT 时插入：触发器拒绝在 PUBLISHED/RETIRED
-- 父行下插入权限。
INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
SELECT gen_random_uuid(), cutover.new_version_id, model.model_code,
       model.model_token_quota, model.capabilities
FROM v104_cutover cutover
JOIN platform.package_plan_version_model model
  ON model.package_plan_version_id = cutover.old_version_id;

-- ---------------------------------------------------------------------------
-- 4. 按新售价重算已存毛利估计（**必须在发布之前**）
--
-- 顺序很关键：已发布行受 `platform.reject_published_version_mutation` 保护，
-- 只有 status → RETIRED 这一种改写被允许。因此重算必须发生在替代版本**仍是
-- DRAFT** 的时候 —— V103 也是这个顺序（它先重算、再发布），本迁移第一版把两步
-- 写反了，于是拿到 `published_version_immutable`。
--
-- 成本基准以字面量写在这里（视频 35/53/69/92/115/345 分/秒、音频
-- ASR/字幕/TTS 2 分/秒、换口型 9 分/秒），与 V56/V61/V63/V64/V66/V84/V103 同一
-- 口径：**迁移是它所依据的那组数字的固定记录**，而不是每次重跑都去问网关。
--
-- 填充方式与 PackageMarginPolicy.assessEveryDimension 同构：允许范围内最贵的
-- 别名优先，每个消耗 min(cap, remaining)，cap 为空表示它可以取走剩余额度
-- （video-minimax 就是这样）。视频用 total_token_quota 作桶、音频用
-- audio_seconds_quota 作桶，各自独立，算完再相加。
--
-- 本迁移**不改**任何额度，所以这里的 total_worst 与迁移前必然相同；
-- 变的只有售价，以及由它决定的毛利。
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
    JOIN v104_cutover cutover ON cutover.new_version_id = version.id
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
    JOIN v104_cutover cutover ON cutover.new_version_id = version.id
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
           coalesce(video_cost.worst, 0) AS video_worst,
           coalesce(audio_cost.worst, 0) AS audio_worst,
           coalesce(video_cost.worst, 0) + coalesce(audio_cost.worst, 0) AS total_worst
    FROM platform.package_plan_version version
    JOIN v104_cutover cutover ON cutover.new_version_id = version.id
    LEFT JOIN video_cost ON video_cost.id = version.id
    LEFT JOIN audio_cost ON audio_cost.id = version.id
)
UPDATE platform.package_plan_version version
SET estimated_cost_amount = assessed.total_worst,
    -- 免费套餐（price_amount = 0，见 V2 种下的 FREE）的毛利率在数学上没有定义，
    -- 不能做除法：本迁移第一版在这里拿到 `division by zero`。它本来也不需要
    -- 毛利 —— 没有收入就没有毛利率。因此 nullif 把 0 变成 NULL，
    -- 结果存 NULL（"不可测"），而不是存一个假的 0 或让迁移失败。
    estimated_margin_ratio = round(
        (assessed.price_amount - assessed.total_worst)::numeric
        / nullif(assessed.price_amount, 0), 5),
    margin_warning = (
        round((assessed.price_amount - assessed.video_worst)::numeric
              / nullif(assessed.price_amount, 0), 5) < 0.35000
        OR round((assessed.price_amount - assessed.audio_worst)::numeric
                 / nullif(assessed.price_amount, 0), 5) < 0.40000)
FROM assessed
WHERE version.id = assessed.id;

-- ---------------------------------------------------------------------------
-- 5. 发布替代版本（重算之后，理由见上）
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version version
SET status = 'PUBLISHED', published_at = now()
FROM v104_cutover cutover
WHERE version.id = cutover.new_version_id AND version.status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 6. 断言：本迁移必须自己证明结果
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    wrong integer;
    bad integer;
BEGIN
    -- 旧版本必须都已退休、新版本必须都已发布、每计划恰好一个在售版本
    SELECT count(*) INTO wrong
    FROM platform.package_plan_version version
    JOIN v104_cutover cutover ON cutover.package_plan_id = version.package_plan_id
    WHERE version.status = 'PUBLISHED' AND version.id <> cutover.new_version_id;
    IF wrong > 0 THEN
        RAISE EXCEPTION 'plan_must_have_exactly_one_published_version:offenders=%', wrong;
    END IF;

    SELECT count(*) INTO wrong
    FROM v104_cutover cutover
    JOIN platform.package_plan_version version ON version.id = cutover.new_version_id
    WHERE version.status <> 'PUBLISHED';
    IF wrong > 0 THEN
        RAISE EXCEPTION 'replacement_version_not_published:count=%', wrong;
    END IF;

    -- 除售价与版本号外，其余各列必须与被替换的那一行逐字相同。
    -- 逐列比对并报出列名，而不是 26 列 AND 成一个大条件：后者失败时
    -- 无法定位（V89 的自检踩过这个坑）。
    SELECT count(*) INTO wrong
    FROM v104_cutover cutover
    JOIN platform.package_plan_version new_version ON new_version.id = cutover.new_version_id
    JOIN platform.package_plan_version old_version ON old_version.id = cutover.old_version_id
    WHERE new_version.price_amount <> cutover.new_price
       OR new_version.currency IS DISTINCT FROM old_version.currency
       OR new_version.total_token_quota IS DISTINCT FROM old_version.total_token_quota
       OR new_version.audio_seconds_quota IS DISTINCT FROM old_version.audio_seconds_quota
       OR new_version.duration_days IS DISTINCT FROM old_version.duration_days
       OR new_version.max_api_keys IS DISTINCT FROM old_version.max_api_keys
       OR new_version.requests_per_minute IS DISTINCT FROM old_version.requests_per_minute
       OR new_version.tokens_per_minute IS DISTINCT FROM old_version.tokens_per_minute
       OR new_version.max_concurrency IS DISTINCT FROM old_version.max_concurrency
       OR new_version.daily_request_limit IS DISTINCT FROM old_version.daily_request_limit
       OR new_version.max_output_tokens IS DISTINCT FROM old_version.max_output_tokens
       OR new_version.max_file_size_bytes IS DISTINCT FROM old_version.max_file_size_bytes
       OR new_version.once_per_user IS DISTINCT FROM old_version.once_per_user
       OR new_version.target_margin_ratio IS DISTINCT FROM old_version.target_margin_ratio
       OR new_version.stock_total IS DISTINCT FROM old_version.stock_total
       OR new_version.low_stock_threshold IS DISTINCT FROM old_version.low_stock_threshold
       OR new_version.max_purchases_per_user IS DISTINCT FROM old_version.max_purchases_per_user
       OR new_version.usage_dimension IS DISTINCT FROM old_version.usage_dimension;
    IF wrong > 0 THEN
        RAISE EXCEPTION 'replacement_changed_something_besides_the_price:count=%', wrong;
    END IF;

    -- 模型权限必须逐字继承（本迁移不动模型集合）
    SELECT count(*) INTO wrong
    FROM v104_cutover cutover
    WHERE (SELECT count(*) FROM platform.package_plan_version_model model
           WHERE model.package_plan_version_id = cutover.new_version_id)
       <> (SELECT count(*) FROM platform.package_plan_version_model model
           WHERE model.package_plan_version_id = cutover.old_version_id);
    IF wrong > 0 THEN
        RAISE EXCEPTION 'replacement_changed_the_model_set:count=%', wrong;
    END IF;

    -- 存储的毛利必须与「售价 − 重算成本」一致（免费套餐存 NULL，跳过）
    SELECT count(*) INTO bad
    FROM platform.package_plan_version version
    JOIN v104_cutover cutover ON cutover.new_version_id = version.id
    WHERE version.estimated_margin_ratio IS DISTINCT FROM
          round((version.price_amount - version.estimated_cost_amount)::numeric
                / nullif(version.price_amount, 0), 5);
    IF bad > 0 THEN
        RAISE EXCEPTION 'stored_margin_disagrees_with_the_repricing:count=%', bad;
    END IF;

    -- **本迁移存在的理由**：涨价之后，两个套餐都必须回到硬下限之上，
    -- 且总成本必须被售价覆盖。这两条若不为真，本迁移就是白做的。
    SELECT count(*) INTO bad
    FROM platform.package_plan_version version
    JOIN v104_cutover cutover ON cutover.new_version_id = version.id
    WHERE version.estimated_cost_amount > version.price_amount
       OR version.estimated_margin_ratio < 0.25;
    IF bad > 0 THEN
        RAISE EXCEPTION
            'video_lite_repricing_did_not_restore_the_margin_floor:count=%', bad;
    END IF;

    -- **关闭 V103 留下的那条有期限豁免。**
    --
    -- V103 对 VIDEO_LITE / VIDEO_LITE_ANNUAL 的「总成本超过售价」降级为
    -- RAISE WARNING（理由与顺序问题写在它的注释里：修复只能追加在它之后）。
    -- 本迁移就是那次修复，因此这里要求**全库**再无任何一个在售版本
    -- 的总成本超过售价 —— 豁免到此结束。
    --
    -- 这一条同时是"豁免没有被滥用"的证明：若将来有人再往目录里塞一个
    -- 亏本版本，它会在这里失败，而不是靠 V103 那张豁免名单继续蒙混。
    SELECT count(*) INTO bad
    FROM platform.package_plan_version version
    WHERE version.status = 'PUBLISHED'
      AND version.estimated_cost_amount IS NOT NULL
      AND version.estimated_margin_ratio IS NOT NULL
      -- 只看本次处置覆盖到的两个视频套餐之外的情形由 V103 负责；
      -- 这里要求的是"总成本不超过售价"这条全局不变量。
      AND version.estimated_cost_amount > version.price_amount;
    IF bad > 0 THEN
        RAISE EXCEPTION
            'published_version_costs_more_than_it_sells_for:count=%', bad;
    END IF;
END $$;
