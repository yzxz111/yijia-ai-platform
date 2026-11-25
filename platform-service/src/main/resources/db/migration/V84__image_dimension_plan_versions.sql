-- 图片（按张）套餐：一个独立的在售计划「图片生成套餐」，而不是给高级版与企业版
-- 各加一个 IMAGE 版本。
--
-- 目的：把 V83 登记并按张计价的别名 image-basic 真正卖出去。它在目录里是一个
-- **独立计划**（IMAGE_BASIC），与视频那五档的做法完全一致（V54 的 VIDEO_STARTER /
-- VIDEO_CREATOR / VIDEO_PRO、V66 的 VIDEO_LITE / VIDEO_LITE_ANNUAL）：一件商品
-- 一个计划、一个在售版本。
--
-- 为什么不给高级版与企业版各加一个图片版本：目录按计划只投影**版本号最大**的那个
-- 已发布版本（PackageVersionRepository.findPublishedPlans 的
-- DISTINCT ON (p.id) ... ORDER BY v.version DESC）。高级版一旦有了 v4（图片），
-- 门店里的「高级版」就变成 ¥49.9 的 48 张图，而 ¥399 的文本套餐**从目录里消失**
-- ——它还在库里、还有客户在用，只是没有任何界面再露出它。下单、快照与限流读的是
-- 同一个投影，因此它们会选择同一件错商品。想要第二件商品，就再建一个计划。
--
-- 「每个计划至多一个在售版本」是 uq_published_package_plan_version（V45:96-98）
-- 写死的目录不变量，本迁移原样保留：不 DROP、不重建那个索引，也不把它的列放宽成
-- (package_plan_id, usage_dimension)。下面的断言块逐条证明这一点（索引定义 + 全部
-- 计划的分组计数），因为一个被放宽的唯一索引会让上面那次「商品消失」在未来的某次
-- 重新发布里悄悄发生。
--
--   计划          版本  价格(分)  额度(张)  张/分钟  每日请求上限  最坏成本  毛利率
--   IMAGE_BASIC   v1      4,990       48       480           200     2,880  42.285%
--
-- 成本基准 60 分/张：上游按张计费 ¥0.30–0.60，分辨率越高越贵，所以基准取峰值
-- 而不是均值 —— 存一个偏低的值会让毛利守卫放行一个在高档位上亏钱的售价。
-- 这与 V83 写进 platform.model_usage_dimension 的 effective_unit_cost = 60 是同一个数。
-- IMAGE 行的 quota_units_per_cost_unit = 1，所以 48 张 = 2,880 分。
--
-- 这些数字不是手抄进来的：成本与毛利率在 INSERT 里按 PackageMarginPolicy.assess 的
-- 算式算出（单价 × 额度 / 每成本单位的额度数，向上取整；毛利率 (价 − 成本)/价，
-- 5 位小数 HALF_UP），两个下限从 platform.package_dimension_price_policy 的 IMAGE 行
-- 读取 —— 守卫在每次创建与每次发布时就是这么做的。下面的断言块再把算出来的值与
-- 上面这张表逐位对照：算错就拒绝迁移，而不是把一个没人复核过的毛利率写进控制台。
--
-- 顺序由触发器决定，不是可选的：版本先以 DRAFT 插入，再插它的模型权限，最后才
-- 发布。platform.reject_published_model_mutation 在父版本处于 PUBLISHED 或 RETIRED 时
-- 拒绝插入权限行（V45:60-79），platform.reject_published_version_mutation 只放行
-- DRAFT 行上的改动与 PUBLISHED → RETIRED（V45:34-58）。本迁移什么都不退役。
--
-- 配置列（币种、时长、key 数、请求速率、并发、输出上限、文件大小、库存、每用户
-- 限购、目标毛利率）复制自 VIDEO_LITE v1（00000000-0000-4000-8000-000000000401，
-- V66）：它同样是一个单独的、几十元档、30 天、2 个 key 的按量计费计划，本迁移
-- 有意改动的只有价格、额度、张/分钟与每日请求上限。max_output_tokens 取 4096 是
-- V66 已经说明过的那个理由：V64 的守卫拒绝任何 max_output_tokens 非正的已发布行，
-- 而图片计费与文本输出上限无关。
--
-- 成本基准以字面量内嵌，而不是去读网关的价目表：迁移是它所依据的那组数字的固定
-- 记录（V56/V61/V63/V64/V66 都这么做），而 platform 的 Flyway 只拥有 platform
-- schema，跨 schema 读取会让每一次「只跑平台侧」的 Testcontainers 运行失败。

-- ---------------------------------------------------------------------------
-- 1. 计划行
-- ---------------------------------------------------------------------------
--
-- display_order 排在视频那五档（36/37/40/50/60）之后：门店里先文本、再视频、
-- 最后按张计费的图片。kind 与其它付费计划一致取 STANDARD（FREE 才是 FREE_CLAIM）。
--
-- ON CONFLICT DO NOTHING 是照抄 V54/V66 的写法，但这里跟一条断言：若 IMAGE_BASIC
-- 已被别的行占用，本迁移必须点名失败，而不是让下面的 INSERT 撞一个外键错。

INSERT INTO platform.package_plan(
    id, code, name, description, kind, status, display_order)
VALUES
    ('00000000-0000-4000-8000-000000000016', 'IMAGE_BASIC', '图片生成套餐',
     '按张计费的文生图 / 图生图套餐。', 'STANDARD', 'ACTIVE', 70)
ON CONFLICT (code) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM platform.package_plan
            WHERE id = '00000000-0000-4000-8000-000000000016'
              AND code = 'IMAGE_BASIC') THEN
        RAISE EXCEPTION 'image_plan_row_not_created:%', 'IMAGE_BASIC';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. 版本以 DRAFT 插入
-- ---------------------------------------------------------------------------
--
-- total_token_quota 承载张数、tokens_per_minute 承载张/分钟：这正是 V54 定下的
-- 「维度承载列」约定，列名不改。
--
-- 成本与毛利率在 SQL 里现算：quota_units_per_cost_unit 与 warning_margin 取自
-- IMAGE 策略行（守卫读的是同一行），单价 60 分是内嵌的成本基准。
-- margin_warning 用的是存下来的那个 5 位小数毛利率与预警下限的比较 —— 守卫比较的
-- 也是四舍五入之后的值（PackageMarginPolicy.assess 先算 scale 5 的 marginRatio）。

WITH offered(price_amount, image_quota, images_per_minute, daily_request_limit, unit_cost) AS (
    VALUES (4990::bigint, 48::bigint, 480::bigint, 200::integer, 60::bigint)
), priced AS (
    SELECT offered.*,
           policy.warning_margin,
           ceil(offered.image_quota::numeric * offered.unit_cost
                / policy.quota_units_per_cost_unit)::bigint AS estimated_cost_amount
    FROM offered
    CROSS JOIN platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension = 'IMAGE'
), assessed AS (
    SELECT priced.*,
           round((priced.price_amount - priced.estimated_cost_amount)::numeric
                 / priced.price_amount, 5) AS estimated_margin_ratio
    FROM priced
)
INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, price_amount, currency, total_token_quota,
    duration_days, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, target_margin_ratio, status, published_at, retired_at,
    daily_request_limit, max_output_tokens, max_file_size_bytes, once_per_user,
    estimated_cost_amount, estimated_margin_ratio, margin_warning, stock_total,
    low_stock_threshold, max_purchases_per_user, usage_dimension)
SELECT '00000000-0000-4000-8000-000000000501'::uuid,
       '00000000-0000-4000-8000-000000000016'::uuid,
       1, assessed.price_amount, 'CNY', assessed.image_quota,
       30, 2, 20, assessed.images_per_minute,
       2, 0.35000, 'DRAFT', NULL, NULL,
       assessed.daily_request_limit, 4096, 20971520, false,
       assessed.estimated_cost_amount, assessed.estimated_margin_ratio,
       assessed.estimated_margin_ratio < assessed.warning_margin, NULL,
       0, NULL, 'IMAGE'
FROM assessed;

-- ---------------------------------------------------------------------------
-- 3. 恰好一个模型权限：image-basic
-- ---------------------------------------------------------------------------
--
-- 一个 IMAGE 版本只允许装 IMAGE 维度的别名（V54 的单维度约定），而这一档只卖
-- 一个别名。capabilities 带 IMAGE_GEN：图片生成正是这个版本卖的能力，网关在交付时
-- 按它重新校验（package_dimension_capability_mismatch）。TEXT/IMAGE 保留是因为
-- 该别名与其它多模态别名一样也能应答文本与看图请求，只是这个套餐的额度按张计。
--
-- model_token_quota 为 NULL：没有按别名切分的子额度，整个套餐额度归它。

INSERT INTO platform.package_plan_version_model(
    id, package_plan_version_id, model_code, model_token_quota, capabilities)
VALUES
    ('00000000-0000-4000-8000-000000000511',
     '00000000-0000-4000-8000-000000000501', 'image-basic', NULL,
     '["TEXT","IMAGE","IMAGE_GEN"]'::jsonb);

-- ---------------------------------------------------------------------------
-- 4. 发布（触发器允许 DRAFT → PUBLISHED，此后该行不可再改）
-- ---------------------------------------------------------------------------

UPDATE platform.package_plan_version
SET status = 'PUBLISHED', published_at = now()
WHERE id = '00000000-0000-4000-8000-000000000501'
  AND status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- 5. 断言：这个计划按卖出的样子在售，限速合法
-- ---------------------------------------------------------------------------
--
-- 第一段是身份与已售限额：计划存在、ACTIVE、STANDARD，名字与描述就是要卖的那句话；
-- 版本存在、已发布、维度是 IMAGE、价格/张数/张每分钟/每日请求上限就是本迁移要卖的
-- 那组值。published_at 一并断言，因为控制台与运营报表都读它，而 status='PUBLISHED'
-- 并不保证它被写过。
--
-- 第二段是「恰好一个在售版本」：计划在售而没有任何在售版本，等于门店里一个点了
-- 没反应的按钮；两个在售版本则会被下面的目录不变量单独拒绝，但在这里先说清是
-- 这个计划的问题。
--
-- 第三段是 V64 对已发布行、PackageRateCeilingPolicy.SECOND_DIMENSIONS 对新建行都
-- 断言的单位边界：IMAGE 与 VIDEO_SECOND 共享 tokens_per_minute 列的「每分钟单位数」
-- 语义，取值必须落在 1..86400。放进一个 token 量级的数字（例如 500,000）会让控制台
-- 把图片套餐渲染成「每分钟 500,000 张」。

DO $$
DECLARE
    plan_row        record;
    version_row     record;
    published_count integer;
BEGIN
    SELECT plan.id, plan.code, plan.name, plan.description, plan.kind, plan.status,
           plan.display_order
    INTO plan_row
    FROM platform.package_plan plan
    WHERE plan.code = 'IMAGE_BASIC';

    IF plan_row IS NULL THEN
        RAISE EXCEPTION 'image_plan_missing:%', 'IMAGE_BASIC';
    END IF;
    IF plan_row.id <> '00000000-0000-4000-8000-000000000016'::uuid THEN
        RAISE EXCEPTION 'image_plan_id_changed:%:id=%', plan_row.code, plan_row.id;
    END IF;
    IF plan_row.status <> 'ACTIVE' OR plan_row.kind <> 'STANDARD' THEN
        RAISE EXCEPTION 'image_plan_not_on_sale:%:status=%:kind=%',
            plan_row.code, plan_row.status, plan_row.kind;
    END IF;
    IF plan_row.name IS DISTINCT FROM '图片生成套餐'
            OR plan_row.description IS DISTINCT FROM '按张计费的文生图 / 图生图套餐。' THEN
        RAISE EXCEPTION 'image_plan_not_as_offered:%:name=%:description=%',
            plan_row.code, plan_row.name, plan_row.description;
    END IF;
    IF plan_row.display_order <> 70 THEN
        RAISE EXCEPTION 'image_plan_display_order_changed:%:display_order=%',
            plan_row.code, plan_row.display_order;
    END IF;

    SELECT count(*) INTO published_count
    FROM platform.package_plan_version version
    WHERE version.package_plan_id = plan_row.id AND version.status = 'PUBLISHED';

    IF published_count <> 1 THEN
        RAISE EXCEPTION 'image_plan_must_sell_exactly_one_published_version:%:count=%',
            plan_row.code, published_count;
    END IF;

    SELECT version.id, version.version, version.usage_dimension, version.price_amount,
           version.currency, version.total_token_quota, version.tokens_per_minute,
           version.daily_request_limit, version.duration_days, version.max_api_keys,
           version.requests_per_minute, version.max_concurrency, version.published_at
    INTO version_row
    FROM platform.package_plan_version version
    WHERE version.package_plan_id = plan_row.id AND version.status = 'PUBLISHED';

    IF version_row.id <> '00000000-0000-4000-8000-000000000501'::uuid
            OR version_row.version <> 1 THEN
        RAISE EXCEPTION 'image_plan_published_version_is_not_the_seeded_one:%:v%:id=%',
            plan_row.code, version_row.version, version_row.id;
    END IF;
    IF version_row.usage_dimension <> 'IMAGE' THEN
        RAISE EXCEPTION 'image_plan_version_dimension_not_image:%:v%:dimension=%',
            plan_row.code, version_row.version, version_row.usage_dimension;
    END IF;
    IF version_row.published_at IS NULL THEN
        RAISE EXCEPTION 'image_plan_version_not_published:%:v%', plan_row.code,
            version_row.version;
    END IF;
    IF version_row.price_amount <> 4990
            OR version_row.currency <> 'CNY'
            OR version_row.total_token_quota <> 48
            OR version_row.tokens_per_minute <> 480
            OR version_row.daily_request_limit IS DISTINCT FROM 200
            OR version_row.duration_days <> 30
            OR version_row.max_api_keys <> 2
            OR version_row.requests_per_minute <> 20
            OR version_row.max_concurrency <> 2 THEN
        RAISE EXCEPTION 'image_plan_version_not_as_sold:%:price=%:images=%:per_minute=%:daily=%',
            plan_row.code, version_row.price_amount, version_row.total_token_quota,
            version_row.tokens_per_minute, version_row.daily_request_limit;
    END IF;
    IF version_row.tokens_per_minute < 1 OR version_row.tokens_per_minute > 86400 THEN
        RAISE EXCEPTION 'image_plan_version_rate_out_of_range:%:images_per_minute=%',
            plan_row.code, version_row.tokens_per_minute;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 6. 断言：成本基准覆盖目录所售卖的一切（失败即关闭）
-- ---------------------------------------------------------------------------
--
-- 任何已发布版本授予的别名，只要不在这张 (维度, 别名, 单价) 表里，就拒绝迁移。
-- 缺失的基准会让守卫的填充跳过该别名，从而**低估**最坏成本；而守卫自己会以
-- provider_price_missing 拒绝发布。一条比它所检验的守卫更宽松的检查比没有检查
-- 更糟，所以这里是失败即关闭，而不是跳过 —— 这恰好也正是新别名 image-basic
-- 溜进目录时本可以抓住那个洞的地方。
--
-- TOKEN 那一档是每百万 token 多少分，与 V61/V63/V64 以及
-- PlatformMigrationTest 的同一份基准一致；IMAGE 的 60 分/张就是 V83 登记进
-- platform.model_usage_dimension 的 effective_unit_cost。
--
-- 同一趟还断言「这个图片版本恰好授予一个别名，且就是 image-basic，上限为空」：
-- 多授一个别名会让最坏成本与下一段算出的数字脱钩，而这一档只卖一个别名。

DO $$
DECLARE
    granted     record;
    bad_model   text;
    model_count integer;
BEGIN
    FOR granted IN
        SELECT plan.code, version.usage_dimension, model.model_code
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan ON plan.id = version.package_plan_id
        JOIN platform.package_plan_version_model model
          ON model.package_plan_version_id = version.id
        WHERE version.status = 'PUBLISHED'
          AND NOT EXISTS (
                SELECT 1 FROM (VALUES
                    ('TOKEN', 'file-basic', 200), ('TOKEN', 'text-basic', 800),
                    ('TOKEN', 'text-standard', 900), ('TOKEN', 'text-pro', 2700),
                    ('TOKEN', 'text-max', 3600), ('TOKEN', 'vision-basic', 720),
                    ('TOKEN', 'vision-pro', 1200), ('TOKEN', 'multimodal-basic', 800),
                    ('VIDEO_SECOND', 'video-max', 345), ('VIDEO_SECOND', 'video-standard', 115),
                    ('VIDEO_SECOND', 'video-pro', 92), ('VIDEO_SECOND', 'video-basic', 69),
                    ('VIDEO_SECOND', 'video-budget', 35),
                    ('IMAGE', 'image-basic', 60)
                ) AS known(usage_dimension, model_code, unit_cost)
                WHERE known.usage_dimension = version.usage_dimension
                  AND known.model_code = model.model_code)
        ORDER BY plan.code, model.model_code
    LOOP
        RAISE EXCEPTION 'published_plan_has_unpriced_model:%:dimension=%:model=%',
            granted.code, granted.usage_dimension, granted.model_code;
    END LOOP;

    SELECT count(*),
           string_agg(model.model_code || ':' || coalesce(model.model_token_quota::text, 'NULL')
                      || ':' || replace(model.capabilities::text, ' ', ''),
                      ',' ORDER BY model.model_code)
    INTO model_count, bad_model
    FROM platform.package_plan_version_model model
    WHERE model.package_plan_version_id = '00000000-0000-4000-8000-000000000501';

    IF model_count <> 1
            OR bad_model <> 'image-basic:NULL:["TEXT","IMAGE","IMAGE_GEN"]' THEN
        RAISE EXCEPTION 'image_version_does_not_grant_exactly_the_image_alias:%:models=%',
            'IMAGE_BASIC', bad_model;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 7. 断言：存储的估算就是守卫会算出的数
-- ---------------------------------------------------------------------------
--
-- 重算的是 PackageMarginPolicy.assess 本身：按单位成本从高到低填满整个额度，
-- 每一项取 min(上限, 剩余)，上限为 NULL 时取走剩余部分。这一行只有一个别名、
-- 且它的上限为 NULL，因此它吃掉整个额度。换算因子、单位标签与两个下限都从
-- platform.package_dimension_price_policy 的 IMAGE 行读取，与守卫读的是同一行。
--
-- 四个各自独立的失败点：
--   a) 存储的成本与毛利率必须等于刚刚算出的那一对；
--   b) 毛利率不得低于 30% 硬下限；
--   c) 存储的 margin_warning 必须等于「毛利率 < 预警下限」——发布被标记而不是
--      被拒绝，正是这两个下限之间的区间的意义；
--   d) 算出来的值与本迁移头部那张表逐位对照：（2,880 / 0.42285）。任务书给出的
--      数字与这里的算式若有分歧，本迁移失败，而不是悄悄改掉其中一个。

DO $$
DECLARE
    plan      record;
    worst     bigint;
    floor_    numeric;
    warning_  numeric;
    units_    bigint;
    label_    text;
    margin    numeric;
BEGIN
    SELECT policy.minimum_margin, policy.warning_margin,
           policy.quota_units_per_cost_unit, policy.unit_label
    INTO floor_, warning_, units_, label_
    FROM platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension = 'IMAGE';

    IF floor_ IS NULL OR warning_ IS NULL OR units_ IS NULL THEN
        RAISE EXCEPTION 'image_dimension_price_policy_missing';
    END IF;
    -- 本迁移写入的数字是照着这两个下限与这个换算因子算的（V58 的 0.30000、
    -- V62 的 0.40000、V83 把单位标成 images；V65 明确保持 IMAGE 不变）。
    -- 它们被改动时这里必须失败，而不是让发布依据一个没人复核过的下限悄悄变松。
    IF floor_ <> 0.30000 OR warning_ <> 0.40000 THEN
        RAISE EXCEPTION 'image_dimension_margin_floor_changed:floor=%:warning=%',
            floor_, warning_;
    END IF;
    IF units_ <> 1 OR label_ <> 'images' THEN
        RAISE EXCEPTION 'image_dimension_unit_changed:units_per_cost_unit=%:label=%',
            units_, label_;
    END IF;

    FOR plan IN
        SELECT version.id, plan_row.code, version.price_amount,
               version.total_token_quota, version.estimated_cost_amount,
               version.estimated_margin_ratio, version.margin_warning
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.id = '00000000-0000-4000-8000-000000000501'
    LOOP
        -- 守卫的填充，逐项 10 位小数四舍五入后向上取整（PackageMarginPolicy
        -- 的 divide(10, HALF_UP) 与 setScale(0, CEILING)），并除以该维度自己的
        -- 换算因子：IMAGE 是每张图一个成本单位，不是每百万。
        WITH basis(usage_dimension, model_code, unit_cost) AS (
            VALUES ('IMAGE', 'image-basic', 60)
        ), perms AS (
            SELECT model.model_token_quota AS cap, basis.unit_cost
            FROM platform.package_plan_version_model model
            JOIN basis ON basis.model_code = model.model_code
                      AND basis.usage_dimension = 'IMAGE'
            WHERE model.package_plan_version_id = plan.id
        ), filled AS (
            SELECT cap, unit_cost,
                   least(coalesce(cap, plan.total_token_quota),
                         greatest(plan.total_token_quota - coalesce(
                             sum(coalesce(cap, plan.total_token_quota)) OVER (
                                 ORDER BY unit_cost DESC, cap
                                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
            FROM perms
        )
        SELECT coalesce(ceil(sum(round(consumed * unit_cost / units_::numeric, 10))), 0)::bigint
        INTO worst
        FROM filled;

        IF worst <= 0 THEN
            RAISE EXCEPTION 'image_plan_unpriceable:%:no_cost_basis', plan.code;
        END IF;
        IF worst > plan.price_amount THEN
            RAISE EXCEPTION 'image_plan_loses_money:%:price=%:worst=%',
                plan.code, plan.price_amount, worst;
        END IF;
        IF worst <> plan.estimated_cost_amount THEN
            RAISE EXCEPTION 'image_plan_estimate_disagrees:%:stored=%:computed=%',
                plan.code, plan.estimated_cost_amount, worst;
        END IF;

        margin := (plan.price_amount - worst)::numeric / plan.price_amount;
        IF round(margin, 5) <> plan.estimated_margin_ratio THEN
            RAISE EXCEPTION 'image_plan_margin_ratio_disagrees:%:stored=%:computed=%',
                plan.code, plan.estimated_margin_ratio, round(margin, 5);
        END IF;
        IF round(margin, 5) < floor_ THEN
            RAISE EXCEPTION 'image_plan_below_floor:%:margin=%:floor=%',
                plan.code, round(margin, 5), floor_;
        END IF;
        IF plan.margin_warning <> (round(margin, 5) < warning_) THEN
            RAISE EXCEPTION 'image_plan_warning_flag_disagrees:%:flag=%:margin=%',
                plan.code, plan.margin_warning, round(margin, 5);
        END IF;
        IF worst <> 2880 OR round(margin, 5) <> 0.42285 THEN
            RAISE EXCEPTION 'image_plan_estimate_differs_from_the_approved_numbers:cost=%:margin=%',
                worst, round(margin, 5);
        END IF;
    END LOOP;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'image_plan_version_missing:%:v%', 'IMAGE_BASIC', 1;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 8. 断言：本迁移没有动到任何既有商品
-- ---------------------------------------------------------------------------
--
-- 这是那次「给高级版与企业版各加一个图片版本」被撤销的回归守卫。
--
-- **断言的是「相对本次迁移没有变化」，而不是「等于某个版本号」。** 早先的写法把
-- 本地库当时的状态（高级版 v3/…0123、企业版 v4/…0224）连同价格、额度、模型权限
-- 逐字写成了期望值，结果在生产上部署时直接中止启动：生产的高级版从未升过版，
-- 仍停在 v2，于是守卫把「它不是我记的那一版」误判成「本迁移改动了它」，
-- 抛出 published_token_plan_version_changed 并让 Flyway 回滚、
-- platform-service 起不来。
--
-- 版本号是**环境相关**的（各库按自己的历史发布到第几版就停在第几版），
-- 不能当作普适事实来断言。本迁移只往 IMAGE_BASIC 插行、从不触碰既有计划，
-- 因此正确的守卫是下面这三条与版本号无关的性质：
--   (1) 每个既有计划仍恰好有一个在售版本（没有凭空多出第二个）；
--   (2) 没有哪个非图片计划拿到 IMAGE 维度的在售版本——那正是被撤销的做法；
--   (3) 没有哪个非图片计划授予图片别名——权限是发放时被网关读取的那份，
--       一旦泄漏，持有高级版的用户就能白拿生图。
-- 另外把被撤销的那两个版本 id 显式列为「不得复活」，这样即便将来有人用
-- 别的路径把它们写回来，也会在这里被挡住。

DO $$
DECLARE
    offender  record;
    count_    integer;
BEGIN
    FOR offender IN
        SELECT plan_row.code
        FROM platform.package_plan plan_row
        WHERE plan_row.code <> 'IMAGE_BASIC'
        GROUP BY plan_row.code
        HAVING count(*) FILTER (
                   WHERE EXISTS (
                       SELECT 1 FROM platform.package_plan_version version
                       WHERE version.package_plan_id = plan_row.id
                         AND version.status = 'PUBLISHED')) > 1
    LOOP
        SELECT count(*) INTO count_
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE plan_row.code = offender.code AND version.status = 'PUBLISHED';

        RAISE EXCEPTION 'published_plan_gained_a_second_published_version:%:count=%',
            offender.code, count_;
    END LOOP;

    SELECT plan_row.code, version.version, version.usage_dimension
    INTO offender
    FROM platform.package_plan_version version
    JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
    WHERE version.status = 'PUBLISHED'
      AND plan_row.code <> 'IMAGE_BASIC'
      AND version.usage_dimension = 'IMAGE'
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'token_plan_gained_an_image_version:%:v%:dimension=%',
            offender.code, offender.version, offender.usage_dimension;
    END IF;

    SELECT plan_row.code, model.model_code
    INTO offender
    FROM platform.package_plan_version_model model
    JOIN platform.package_plan_version version ON version.id = model.package_plan_version_id
    JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
    WHERE version.status = 'PUBLISHED'
      AND plan_row.code <> 'IMAGE_BASIC'
      AND model.model_code = 'image-basic'
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'token_plan_grants_the_image_alias:%:model=%',
            offender.code, offender.model_code;
    END IF;

    SELECT version.id, plan_row.code
    INTO offender
    FROM platform.package_plan_version version
    JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
    WHERE version.id IN ('00000000-0000-4000-8000-000000000123'::uuid,
                         '00000000-0000-4000-8000-000000000224'::uuid)
      AND plan_row.code <> 'IMAGE_BASIC'
      -- 只针对「图片维度」的版本：V63 的数据更正里，高级版 v3/企业版 v4 用的正是这两个 id，
      -- 它们是**普通 token 版本**。少了这个限定，任何全新库（CI、新环境）迁移到这里都会误报
      -- retracted_image_version_on_a_token_plan_reappeared 并中止启动 —— 生产库当年能过，
      -- 只是因为那时这两个 id 的行还不是这个形态。守卫要挡的是"被撤销的**图片**版本复活"。
      AND version.usage_dimension = 'IMAGE'
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION 'retracted_image_version_on_a_token_plan_reappeared:%:id=%',
            offender.code, offender.id;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 9. 断言：目录仍然「每个计划至多一个在售版本」
-- ---------------------------------------------------------------------------
--
-- 这条不变量由 V45 的 uq_published_package_plan_version 强制
-- （UNIQUE(package_plan_id) WHERE status='PUBLISHED'）。本迁移不重建它、也不放宽它，
-- 因此这里既断言数据（没有任何计划有两个在售版本），也断言索引定义本身
-- （一旦有人把它改成 (package_plan_id, usage_dimension)，这条断言就失败，而不是
-- 等到下一次「重新发布高级版」把某个在售商品从目录里挤掉时才被发现）。

DO $$
DECLARE
    offenders text;
    index_def text;
BEGIN
    SELECT string_agg(multi.code || '=' || multi.published_count, ',' ORDER BY multi.code)
    INTO offenders
    FROM (
        SELECT plan_row.code, count(*) AS published_count
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.status = 'PUBLISHED'
        GROUP BY plan_row.code
        HAVING count(*) > 1
    ) multi;

    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'plan_has_more_than_one_published_version:%', offenders;
    END IF;

    SELECT idx.indexdef INTO index_def
    FROM pg_indexes idx
    WHERE idx.schemaname = 'platform'
      AND idx.indexname = 'uq_published_package_plan_version';

    IF index_def IS NULL THEN
        RAISE EXCEPTION 'published_plan_uniqueness_index_missing:%',
            'uq_published_package_plan_version';
    END IF;
    IF index_def NOT LIKE 'CREATE UNIQUE INDEX%'
            OR index_def NOT LIKE '%(package_plan_id)%'
            OR index_def NOT LIKE '%PUBLISHED%'
            OR index_def LIKE '%usage_dimension%' THEN
        RAISE EXCEPTION 'published_plan_uniqueness_index_widened:%', index_def;
    END IF;
END $$;
