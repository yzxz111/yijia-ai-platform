-- 图片（按张）维度的计量单位与目录登记。
--
-- 平台侧早在 V54 就把 IMAGE 写进了三个 CHECK（版本维度、读模型维度、
-- 价格策略维度），但那个维度从来没有任何一行数据：既没有模型，
-- 也没有费率行，也没有套餐。更糟的是它的单位被写成了「秒」——
-- V54 把 IMAGE 行插成
--
--     ('IMAGE', 1, 'seconds', 0.25000, 0.30000)
--
-- 而该表的 CHECK 只允许 'tokens' 与 'seconds'，所以当时除了「照抄
-- 视频那一行」没有别的选择。按张计费却标成秒，会让运维在控制台里
-- 读到「图片套餐每分钟 60 秒」这种没有意义的话，也会让毛利告警的
-- 文案把单位说错。本迁移把 label 词表扩到 'images' 并改正该行。
--
-- 同时登记图片别名的计量维度。platform.model_usage_dimension 的语义是
-- 「没有行 = TOKEN 别名」（V54:124-126、ModelUsageDimensionRepository:59-70），
-- 因此一个按张计费的别名若不在此登记，它的套餐会被当成 token 套餐
-- 发布，然后在发放时被网关的权益触发器拒绝——而那时距运维本可以
-- 看出原因的时点已经过去很久。这次写入正是用来防止那种情况的。

-- ---------------------------------------------------------------------------
-- 1. 单位词表：允许 'images'
-- ---------------------------------------------------------------------------

ALTER TABLE platform.package_dimension_price_policy
    DROP CONSTRAINT ck_package_dimension_policy_label;

ALTER TABLE platform.package_dimension_price_policy
    ADD CONSTRAINT ck_package_dimension_policy_label
        CHECK (unit_label IN ('tokens', 'seconds', 'images'));

-- 只动单位标签。下限不动：IMAGE 的 0.30000/0.40000 是 V58 与 V62 抬上来的，
-- V65 明确保持了 IMAGE 不变（V65:16-25），这里没有理由再改一次。
UPDATE platform.package_dimension_price_policy
SET unit_label = 'images'
WHERE usage_dimension = 'IMAGE'
  AND unit_label = 'seconds';

-- ---------------------------------------------------------------------------
-- 2. 读模型的档位词表：允许图片档位
-- ---------------------------------------------------------------------------

-- resolution_tier 原本只认视频档位。图片档位是 1K/2K/4K，
-- 与 480P/720P/1080P 不是同一套阶梯，也不是它的子集，
-- 所以必须显式加进来，而不是把 2K 硬塞成 '1080P' 去骗过约束——
-- 那会让按分辨率加权的成本基准失去依据。

ALTER TABLE platform.model_usage_dimension
    DROP CONSTRAINT ck_model_usage_dimension_tier;

ALTER TABLE platform.model_usage_dimension
    ADD CONSTRAINT ck_model_usage_dimension_tier
        CHECK (resolution_tier IN ('', '480P', '720P', '1080P', '1K', '2K', '4K'));

-- ---------------------------------------------------------------------------
-- 3. 登记图片别名的计量维度
-- ---------------------------------------------------------------------------
--
-- 价格两列只是对网关价目表所含内容的记录：V54 的注释已经说明
-- 发布校验改为从网关读取实时数值（/internal/prices/effective-unit-cost），
-- 因此这两列可以在这里填写，但它们并不是套餐定价所依据的数值。
-- 权威值在 gateway.model_usage_price 的同名费率行上。
--
-- effective_unit_cost 取分辨率加权的输出图成本上限：上游按张计费
-- ¥0.30–0.60，峰值基准取 60 分/张。零售 86 分/张 ≈ 成本 60 分
-- 在 30% 硬下限之上的最小可发布价（ceil(60 / 0.70) = 86）。
-- 分辨率越高成本越高，所以基准取最高档而不是平均档：
-- 存一个偏低的值会让毛利守卫放行一个在高档位上亏钱的售价。

INSERT INTO platform.model_usage_dimension(
    model_code, usage_dimension, resolution_tier, retail_unit_price,
    effective_unit_cost, price_version)
VALUES
    ('image-basic', 'IMAGE', '2K', 86, 60, 'image-v1')
ON CONFLICT (model_code) DO UPDATE SET
    usage_dimension = EXCLUDED.usage_dimension,
    resolution_tier = EXCLUDED.resolution_tier,
    retail_unit_price = EXCLUDED.retail_unit_price,
    effective_unit_cost = EXCLUDED.effective_unit_cost,
    price_version = EXCLUDED.price_version,
    synced_at = now();

-- ---------------------------------------------------------------------------
-- 4. 断言：词表与登记确实生效
-- ---------------------------------------------------------------------------
--
-- 与 V54/V58/V62/V65 的做法一致：一个直接写入受约束表的迁移
-- 必须自己证明写入是合法的，否则约束被放宽或被超级用户绕过时
-- 没有任何东西会失败。这里断言的是「本迁移写入的行确实存在且合规」，
-- 不是一个恒真的 CHECK 复述。

DO $$
DECLARE
    image_label text;
    image_dimension text;
    image_tier text;
BEGIN
    SELECT unit_label INTO image_label
    FROM platform.package_dimension_price_policy
    WHERE usage_dimension = 'IMAGE';

    IF image_label IS DISTINCT FROM 'images' THEN
        RAISE EXCEPTION 'image_dimension_unit_label_not_images:%', image_label;
    END IF;

    SELECT usage_dimension, resolution_tier
    INTO image_dimension, image_tier
    FROM platform.model_usage_dimension
    WHERE model_code = 'image-basic';

    IF image_dimension IS DISTINCT FROM 'IMAGE' THEN
        RAISE EXCEPTION 'image_alias_dimension_not_image:%', image_dimension;
    END IF;

    IF image_tier IS DISTINCT FROM '2K' THEN
        RAISE EXCEPTION 'image_alias_tier_not_2k:%', image_tier;
    END IF;
END;
$$;
