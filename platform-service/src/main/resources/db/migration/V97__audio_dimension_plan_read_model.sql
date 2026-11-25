-- 音频别名的平台侧计量维度登记。
--
-- V96 把 AUDIO_SECOND 加进了 platform.model_usage_dimension 的维度词表，
-- 但**刻意没有登记任何别名**——那时网关侧还没有音频别名，而给一个不存在的
-- 别名写一行读模型，会让这张表表达一个当时并不成立的断言。
-- 网关 V101 建立了三个音频别名（audio-asr-basic、audio-tts-basic、audio-retalk）
-- 与它们的费率行，因此现在可以登记它们。
--
-- 为什么这次登记是「能不能发布音频套餐」的关键：platform.model_usage_dimension
-- 的语义是「没有行 = TOKEN 别名」（V54:124-126、ModelUsageDimensionRepository）。
-- 一个按秒计费的别名若不在此登记，平台会把它当成 token 模型，
-- 于是它的套餐会被当成 token 套餐发布，然后在发放时被网关的权益触发器拒绝——
-- 而那时距运维本可以第一次看出原因的时点已经过去很久。V83:15-19 为图片
-- 写过完全相同的理由。
--
-- 版本号 89：V96 是音频词表与价格策略，V87 被
-- V87__verified_phone_registration_completion_window.sql 占用。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
-- 参照：platform V83（图片别名的登记）、V54（视频别名的登记）

-- ---------------------------------------------------------------------------
-- 1. 登记三个音频别名
--
-- 价格两列只是对网关价目表所含内容的记录：V54 的注释已经说明发布校验改为
-- 从网关读取实时数值（/internal/prices/effective-unit-cost），
-- 因此这两列可以在这里填写，但它们并不是套餐定价所依据的数值。
-- 权威值在 gateway.model_usage_price 的同名费率行上（网关 V101）。
--
-- **effective_unit_cost 存的是视图计算出来的有效成本，也就是 ceil 之后的整数。**
-- 这一点必须照抄网关侧的算术，否则两张表会给出不同的成本基准，
-- 而控制台读的是这一张。逐行：
--
--   audio-asr-basic  provider_unit_cost 1、retry_rate 0.05
--                    -> ceil(1 * 1.05) = ceil(1.05) = 2
--                    零售 5 分/秒
--   audio-tts-basic  provider_unit_cost 1、retry_rate 0.05
--                    -> ceil(1 * 1.05) = 2
--                    零售 5 分/秒
--   audio-retalk     provider_unit_cost 8、retry_rate 0.05
--                    -> ceil(8 * 1.05) = ceil(8.4) = 9
--                    零售 14 分/秒（绝对下限 9 + 5 是约束方，比率下限 13 不够）
--
-- ASR/TTS 的成本基准存 1 而不是厂商原价 0.022 / 0.02：provider_unit_cost 是
-- bigint，写小数会被静默四舍五入成 0（网关 V101 的注释里有完整说明）。
-- 网关侧取整后，这里的镜像必须跟着取整，否则两张表给出不同基准。
--
-- resolution_tier 用 ''：音频的单位就是秒，没有尺寸或分辨率阶梯，
-- 因此它的行与 TOKEN 行同形。V83 已经把 '' 留在词表里。
-- ---------------------------------------------------------------------------

INSERT INTO platform.model_usage_dimension(
    model_code, usage_dimension, resolution_tier, retail_unit_price,
    effective_unit_cost, price_version)
VALUES
    ('audio-asr-basic', 'AUDIO_SECOND', '', 5, 2, 'audio-v1'),
    ('audio-tts-basic', 'AUDIO_SECOND', '', 5, 2, 'audio-v1'),
    ('audio-retalk', 'AUDIO_SECOND', '', 14, 9, 'audio-v1')
ON CONFLICT (model_code) DO UPDATE SET
    usage_dimension = EXCLUDED.usage_dimension,
    resolution_tier = EXCLUDED.resolution_tier,
    retail_unit_price = EXCLUDED.retail_unit_price,
    effective_unit_cost = EXCLUDED.effective_unit_cost,
    price_version = EXCLUDED.price_version,
    synced_at = now();

COMMENT ON TABLE platform.model_usage_dimension IS
    'Read model of the gateway rate card: which aliases are priced per second, '
    'per image, or per audio second. Used to validate a package version''s '
    'usage_dimension before publication. An alias with no row here is a TOKEN '
    'alias (V54:124-126).';

-- ---------------------------------------------------------------------------
-- 2. 断言
--
-- 与 V83:87-91 同一做法：一个直接写入受约束表的迁移必须自己证明写入合法，
-- 而且必须证明**每一个**维度值都能真的被写进去。后者尤其重要，
-- 因为本迁移写的 AUDIO_SECOND 是 V96 刚刚加进词表的那个值——
-- 若 V96 的 ALTER 没有生效，这里会以 check_violation 失败，
-- 而不是静默地一行都没写。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    registered    integer;
    wrong         integer;
    expected      record;
BEGIN
    -- 三个别名都必须登记，而且维度必须恰好是 AUDIO_SECOND。
    SELECT count(*) INTO registered
    FROM platform.model_usage_dimension dimension
    WHERE dimension.usage_dimension = 'AUDIO_SECOND';

    IF registered <> 3 THEN
        RAISE EXCEPTION
            'audio_read_model_must_register_exactly_three_aliases:actual=%', registered;
    END IF;

    -- 逐行比对 (零售, 有效成本) 与网关侧的算术。这里复述的是**推导出来的**
    -- 数字，而不是从本文件的 INSERT 里读回来的同一份字面量：
    -- 一个把 9 写成 8 的手算错误不会违反任何约束，却会让控制台显示的
    -- 成本基准与网关实际结算所用的一致却不正确。
    FOR expected IN
        SELECT * FROM (VALUES
            ('audio-asr-basic',   5::bigint,  2::bigint),
            ('audio-tts-basic',   5::bigint,  2::bigint),
            ('audio-retalk',     14::bigint,  9::bigint)
        ) AS e(model_code, retail, cost)
    LOOP
        SELECT count(*) INTO wrong
        FROM platform.model_usage_dimension dimension
        WHERE dimension.model_code = expected.model_code
          AND dimension.usage_dimension = 'AUDIO_SECOND'
          AND dimension.resolution_tier = ''
          AND dimension.retail_unit_price = expected.retail
          AND dimension.effective_unit_cost = expected.cost
          AND dimension.price_version = 'audio-v1';

        IF wrong <> 1 THEN
            RAISE EXCEPTION
                'audio_read_model_row_does_not_match_the_gateway_rate_card:'
                'model=%:expected_retail=%:expected_cost=%',
                expected.model_code, expected.retail, expected.cost;
        END IF;
    END LOOP;

    -- 登记不得顺手把维度改错：三个别名都必须是 AUDIO_SECOND，
    -- 一个被登记成 VIDEO_SECOND 的音频别名会让它的套餐按秒数卖成视频秒，
    -- 而视频秒的成本贵两个数量级。
    SELECT count(*) INTO wrong
    FROM platform.model_usage_dimension dimension
    WHERE dimension.model_code IN ('audio-asr-basic', 'audio-tts-basic', 'audio-retalk')
      AND dimension.usage_dimension <> 'AUDIO_SECOND';

    IF wrong > 0 THEN
        RAISE EXCEPTION
            'an_audio_alias_was_registered_under_the_wrong_dimension:count=%', wrong;
    END IF;
END $$;
