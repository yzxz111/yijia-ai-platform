-- 图片镜像价目表：把 image-basic 挪到「含输入图的最坏情况基准」62 分/张，零售 89 分/张。
--
-- 背景（产品负责人 2026-09-28 的裁定）：图生图时厂商对输入图另收 ¥0.02/张 = 2 分，
-- 这笔钱必须由客户承担，而不是平台吸收。因此网关费率卡从「只算输出图」的 60 分/张
-- 挪到「输出图最坏情况 60 分 + 输入图 2 分 = 62 分/张」。网关侧由
-- gateway V82__image_layer_and_input_cost_basis.sql 落地；本迁移是同一件事在
-- platform.model_usage_dimension 上的镜像，两张表必须同时改，否则控制台读到的成本
-- 与发布守卫实际据以定价的成本会不一致。
--
--   成本 62 分/张，零售 89 分/张（¥0.89），版本 image-v2
--   89 是同时越过两条下限的最小整数：
--     绝对下限：unit_price - provider_unit_cost >= min_absolute_margin  -> 89 - 62 = 27 >= 25
--     比率下限：unit_price >= ceil(62 / (1 - 0.30)) = ceil(88.571) = 89
--   87 只满足绝对下限，毛利率 28.7%，低于产品对非 token 维度承诺的 30%，
--   因此 89 才是那个「2 分输入成本由客户付、同时不击穿下限」的价格。
--   每张最坏情况毛利 27 分（¥0.27），输出图实际按低于 60 分结算时更高。
--
-- 本迁移**不动套餐价格**。IMAGE_BASIC v1（48 张 / 4,990 分）继续在售：
-- 按新的 62 分基准，它的真实毛利是 (4,990 − 48×62) / 4,990 = 40.36%，仍远高于 30%
-- 硬下限，因此不需要下架、也不需要重新发布。
--
-- 本迁移不种任何凭据、不改任何渠道或供应商行、不改维度与档位（仍是 IMAGE / 2K）。

-- ---------------------------------------------------------------------------
-- 1. 镜像价目表
--
-- price_version 一并改成 image-v2：它记录的是「这一行对应网关费率卡的哪一版」，
-- 留着 image-v1 会让运维以为镜像还停在旧基准上。
-- 与 V83 相同的 upsert：该行的存在已经是 V83 的责任，这里只负责把它更新到
-- 本迁移写入的那组数字（model_code 是主键，因此按它冲突）。
-- ---------------------------------------------------------------------------

INSERT INTO platform.model_usage_dimension(
    model_code, usage_dimension, resolution_tier, retail_unit_price,
    effective_unit_cost, price_version)
VALUES
    ('image-basic', 'IMAGE', '2K', 89, 62, 'image-v2')
ON CONFLICT (model_code) DO UPDATE SET
    usage_dimension = EXCLUDED.usage_dimension,
    resolution_tier = EXCLUDED.resolution_tier,
    retail_unit_price = EXCLUDED.retail_unit_price,
    effective_unit_cost = EXCLUDED.effective_unit_cost,
    price_version = EXCLUDED.price_version,
    synced_at = now();

-- ---------------------------------------------------------------------------
-- 2. 断言：镜像就是要求的数字，且维度/档位没有被顺手改掉
--
-- 与 V83 的做法一致：一个直接写入受约束表的迁移必须自己证明写入是合法的，
-- 否则约束被放宽或被超级用户绕过时没有任何东西会失败。这里断言的是
-- 「本迁移写入的行确实存在且就是那组数字」，不是一个恒真的 CHECK 复述。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    image_dimension text;
    image_tier      text;
    image_cost      bigint;
    image_retail    bigint;
    image_version   text;
    floor_          numeric;
BEGIN
    SELECT usage_dimension, resolution_tier, effective_unit_cost,
           retail_unit_price, price_version
    INTO image_dimension, image_tier, image_cost, image_retail, image_version
    FROM platform.model_usage_dimension
    WHERE model_code = 'image-basic';

    IF image_dimension IS DISTINCT FROM 'IMAGE' THEN
        RAISE EXCEPTION 'image_alias_dimension_not_image:%', image_dimension;
    END IF;
    IF image_tier IS DISTINCT FROM '2K' THEN
        RAISE EXCEPTION 'image_alias_tier_not_2k:%', image_tier;
    END IF;
    IF image_cost IS DISTINCT FROM 62 THEN
        RAISE EXCEPTION 'image_effective_unit_cost_not_the_worst_case_basis:%', image_cost;
    END IF;
    IF image_retail IS DISTINCT FROM 89 THEN
        RAISE EXCEPTION 'image_retail_unit_price_not_89:%', image_retail;
    END IF;
    IF image_version IS DISTINCT FROM 'image-v2' THEN
        RAISE EXCEPTION 'image_price_version_not_v2:%', image_version;
    END IF;

    -- 零售 89 是同时越过两条下限的价格。下限从策略行读取（发布守卫读的是同一行），
    -- 因为 89 之所以是 89，正是因为这两条：改了下限，这个价格就可能不再成立。
    SELECT policy.minimum_margin INTO floor_
    FROM platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension = 'IMAGE';

    IF floor_ IS NULL THEN
        RAISE EXCEPTION 'image_dimension_price_policy_missing';
    END IF;
    IF image_retail - image_cost < 25 THEN
        RAISE EXCEPTION 'image_price_below_the_absolute_floor:price=%:cost=%',
            image_retail, image_cost;
    END IF;
    IF image_retail < ceil(image_cost::numeric / (1 - floor_)) THEN
        RAISE EXCEPTION 'image_price_below_the_ratio_floor:price=%:cost=%:floor=%',
            image_retail, image_cost, floor_;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3. 断言：套餐价格没有被改动，已发布版本的存储快照保持原样
--
-- 这一节存在的理由是防止未来的读者（或未来的迁移）把「快照过期」误判成「快照错了」。
--
-- IMAGE_BASIC v1 是 2026-09-28 以 60 分/张 的成本基准发布的，因此它的
-- estimated_cost_amount / estimated_margin_ratio 存的是 2,880 / 0.42285 ——
-- 那是**发布当时**的算法结果。发布之后成本基准变了，而**已发布版本的行是不可变的**
-- （platform.reject_published_version_mutation，V45:34-58：status 为 PUBLISHED 或
-- RETIRED 的行，除库存两列与 PUBLISHED→RETIRED 之外，任何 UPDATE 都抛
-- published_version_immutable）。因此这两个数字保持 2,880 / 0.42285 是**预期**
-- 行为，不是一处需要修正的错误；改它们会直接抛异常，下面的断言正是为此：
-- 它证明这两个数字仍然是发布时的值，并证明这个版本仍然在售。
--
-- 真正被重新测量的是运行期：PublishedPlanMarginMonitor 用当前成本基准重算每一个已
-- 发布版本的最坏情况（与发布守卫同一套算式，整个额度先由允许范围内最贵的别名填满），
-- 并在它低于该维度的下限时发出 LOW_MARGIN_PACKAGE 告警。按 62 分/张：
--     最坏成本 = 48 张 × 62 分 = 2,976 分
--     毛利率   = (4,990 − 2,976) / 4,990 = 0.40360... = 40.36%
-- 40.36% 高于 IMAGE 行 0.30000 的硬下限（也高于 0.40000 的预警下限），
-- 因此这个版本**不会**触发 LOW_MARGIN 告警；本节把这两个数字在迁移里算一遍，
-- 让「不会告警」这个结论留下证据，而不是只在某次运行日志里出现过。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    plan_row    record;
    version_row record;
    worst       bigint;
    margin      numeric;
    floor_      numeric;
    warning_    numeric;
BEGIN
    SELECT plan.id, plan.code, plan.status
    INTO plan_row
    FROM platform.package_plan plan
    WHERE plan.code = 'IMAGE_BASIC';

    IF plan_row IS NULL THEN
        RAISE EXCEPTION 'image_plan_missing:%', 'IMAGE_BASIC';
    END IF;
    -- 仍然在售：本迁移不改价格，因此没有理由下架或重新发布。
    IF plan_row.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'image_plan_no_longer_on_sale:%:status=%',
            plan_row.code, plan_row.status;
    END IF;

    SELECT version.id, version.version, version.price_amount, version.total_token_quota,
           version.estimated_cost_amount, version.estimated_margin_ratio,
           version.margin_warning
    INTO version_row
    FROM platform.package_plan_version version
    WHERE version.package_plan_id = plan_row.id AND version.status = 'PUBLISHED';

    IF version_row IS NULL THEN
        RAISE EXCEPTION 'image_plan_without_a_published_version:%', plan_row.code;
    END IF;

    -- 价格与额度一位不改：这本迁移只动成本基准。
    IF version_row.price_amount <> 4990 OR version_row.total_token_quota <> 48 THEN
        RAISE EXCEPTION 'image_plan_price_or_quota_changed:price=%:images=%',
            version_row.price_amount, version_row.total_token_quota;
    END IF;

    -- 发布时的快照保持原样（不可变），这正是它**应该**的样子。
    IF version_row.estimated_cost_amount <> 2880
            OR version_row.estimated_margin_ratio <> 0.42285 THEN
        RAISE EXCEPTION 'published_image_version_snapshot_changed:cost=%:margin=%',
            version_row.estimated_cost_amount, version_row.estimated_margin_ratio;
    END IF;

    SELECT policy.minimum_margin, policy.warning_margin
    INTO floor_, warning_
    FROM platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension = 'IMAGE';

    -- 运行期监控器的重算：整个额度由最贵的别名填满（这一档只有一个别名，
    -- 且它的 model_token_quota 为 NULL，因此它吃掉整个额度）。
    -- ceil 的写法与 PackageMarginPolicy.assess 一致：先按单位成本取整，
    -- 再汇总，最后向上取整到分。
    SELECT ceil(sum(round(coalesce(model.model_token_quota,
                                  version_row.total_token_quota) * 62, 10)))::bigint
    INTO worst
    FROM platform.package_plan_version_model model
    WHERE model.package_plan_version_id = version_row.id;

    IF worst IS DISTINCT FROM 2976 THEN
        RAISE EXCEPTION 'image_plan_worst_case_not_recomputed_from_the_new_basis:worst=%',
            worst;
    END IF;

    margin := round((version_row.price_amount - worst)::numeric / version_row.price_amount, 5);

    IF margin <> 0.40361 THEN
        RAISE EXCEPTION 'image_plan_margin_not_recomputed_from_the_new_basis:margin=%', margin;
    END IF;
    IF margin < floor_ THEN
        RAISE EXCEPTION 'image_plan_below_floor_after_the_new_basis:margin=%:floor=%',
            margin, floor_;
    END IF;
    -- 告警下限也一起看：低于它只会产生 margin_warning 文案，不会开启
    -- LOW_MARGIN_PACKAGE 告警。40.36% 在它之上，因此这里连预警都不该有。
    IF margin < warning_ THEN
        RAISE EXCEPTION 'image_plan_inside_the_margin_warning_band:margin=%:warning=%',
            margin, warning_;
    END IF;
END $$;
