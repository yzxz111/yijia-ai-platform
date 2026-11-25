-- 客户端视频目录收敛为一个「MiniMax」模型（video-minimax），480P/720P 作为它的可提交档位。
--
-- 背景：视频别名按分辨率拆成 video-budget(480P)/video-basic(720P)/video-standard(1080P)/
-- video-pro(720P)/video-max(1080P)，加上 video-minimax(720P)，客户端在下拉里看到 6 个
-- 「同一个底层能力」的入口，还要混着音频别名，太杂乱。产品决定：客户端只显示一个
-- 「MiniMax」入口，分辨率（480P/720P）变成这个模型的参数，由客户端的选择器承载。
--
-- 方案：**展示层合并 + 提交层映射**，不触碰平台侧定价与套餐授权：
--
--   1. 本迁移建立 `gateway.video_client_tier_mapping`：客户端提交
--      model=video-minimax + resolution=480P 时，网关把它解析成内部别名 video-budget
--      （480P 定价、480P 路由、480P 档位校验）；resolution=720P 时解析为 video-minimax
--      自身。计费、路由、档位校验全部沿用内部别名既有的链路，因此
--      `gateway.model_usage_price` 与 `platform.model_usage_dimension` 一行都不用改。
--
--   2. 五个档位别名对客户端目录收起（client_visible = false）——它们仍可被第三方
--      兼容客户端按名字调用；`/v1/video-models` 的目录查询增加 client_visible 过滤，
--      于是客户端只看到 video-minimax。
--
--   3. video-minimax 的展示名改为「MiniMax」。
--
-- 为什么映射目标选 video-budget 而不是给 video-minimax 加第二档价格行：
-- gateway.video_alias_tier 是 (model_code) 主键、每别名一档；platform.model_usage_dimension
-- 同样是 model_code 主键、每模型一档，改成多档会连锁改平台发布与毛利评估的读模型。
-- 映射把「哪个档位多少钱」留在既有别名上，平台侧零改动。

-- ---------------------------------------------------------------------------
-- 1. 映射表
-- ---------------------------------------------------------------------------

CREATE TABLE gateway.video_client_tier_mapping (
    client_model_code varchar(128) NOT NULL
        REFERENCES gateway.model(code) ON DELETE CASCADE,
    resolution_tier varchar(16) NOT NULL,
    internal_model_code varchar(128) NOT NULL
        REFERENCES gateway.model(code) ON DELETE CASCADE,
    position integer NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (client_model_code, resolution_tier),
    CONSTRAINT ck_video_client_tier_value
        CHECK (resolution_tier IN ('480P', '720P', '1080P'))
);

COMMENT ON TABLE gateway.video_client_tier_mapping IS
    '客户端合并模型的档位映射：客户端提交 (client_model_code, resolution_tier) 时，'
    '网关解析成 internal_model_code 走既有的路由/计费/档位校验链路。'
    '内部别名仍按各自一档定价（video_alias_tier / model_usage_price 不变），'
    '因此这份映射只是展示层合并，不改变任何计费语义。';

COMMENT ON COLUMN gateway.video_client_tier_mapping.internal_model_code IS
    '该档位实际执行与计费的内部别名；它必须在 gateway.video_alias_tier 里登记'
    '同一个 resolution_tier（本迁移末尾的断言证明）。';

INSERT INTO gateway.video_client_tier_mapping(
    client_model_code, resolution_tier, internal_model_code, position)
VALUES
    ('video-minimax', '480P', 'video-budget',  1),
    ('video-minimax', '720P', 'video-minimax', 2)
ON CONFLICT (client_model_code, resolution_tier) DO UPDATE SET
    internal_model_code = EXCLUDED.internal_model_code,
    position = EXCLUDED.position;

-- ---------------------------------------------------------------------------
-- 2. 客户端目录收起五个档位别名；video-minimax 改为「MiniMax」
-- ---------------------------------------------------------------------------

UPDATE gateway.model
SET client_visible = false, updated_at = now()
WHERE code IN ('video-basic', 'video-budget', 'video-standard', 'video-pro', 'video-max')
  AND client_visible;

UPDATE gateway.model
SET display_name = 'MiniMax', updated_at = now()
WHERE code = 'video-minimax'
  AND display_name <> 'MiniMax';

-- ---------------------------------------------------------------------------
-- 断言
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken integer;
BEGIN
    -- (a) 映射恰好是那两行：少一行客户端少一个档位，多一行客户端多一个没定价的档位。
    SELECT count(*) INTO broken
    FROM gateway.video_client_tier_mapping mapping
    WHERE mapping.client_model_code <> 'video-minimax'
       OR (mapping.resolution_tier, mapping.internal_model_code)
          NOT IN (('480P', 'video-budget'), ('720P', 'video-minimax'));
    IF broken > 0 THEN
        RAISE EXCEPTION 'unexpected_video_client_tier_mapping:count=%', broken;
    END IF;
    SELECT count(*) INTO broken
    FROM (VALUES ('480P', 'video-budget'), ('720P', 'video-minimax')) AS wanted(tier, internal)
    WHERE NOT EXISTS (
        SELECT 1 FROM gateway.video_client_tier_mapping mapping
        WHERE mapping.client_model_code = 'video-minimax'
          AND mapping.resolution_tier = wanted.tier
          AND mapping.internal_model_code = wanted.internal);
    IF broken > 0 THEN
        RAISE EXCEPTION 'video_client_tier_mapping_incomplete:count=%', broken;
    END IF;

    -- (b) 每个内部别名都登记了与映射档位一致的 video_alias_tier 行：
    --     没有这一行，解析后的请求会在 requireSoldResolution 里 422。
    SELECT count(*) INTO broken
    FROM gateway.video_client_tier_mapping mapping
    LEFT JOIN gateway.video_alias_tier tier
      ON tier.model_code = mapping.internal_model_code
    WHERE tier.model_code IS NULL
       OR tier.resolution_tier <> mapping.resolution_tier;
    IF broken > 0 THEN
        RAISE EXCEPTION 'video_client_tier_mapping_internal_tier_missing:count=%', broken;
    END IF;

    -- (c) 客户端目录里可见的视频别名必须恰好是 video-minimax 一个。
    SELECT count(*) INTO broken
    FROM gateway.model model
    WHERE model.client_visible AND model.enabled
      AND model.capabilities @> '["VIDEO"]'::jsonb
      AND model.code <> 'video-minimax';
    IF broken > 0 THEN
        RAISE EXCEPTION 'tier_video_alias_still_client_visible:count=%', broken;
    END IF;
    SELECT count(*) INTO broken
    FROM gateway.model model
    WHERE model.code = 'video-minimax'
      AND (NOT model.client_visible OR NOT model.enabled OR model.display_name <> 'MiniMax');
    IF broken > 0 THEN
        RAISE EXCEPTION 'consolidated_video_alias_not_visible:count=%', broken;
    END IF;

    -- (d) 被收起的别名必须仍然 enabled：它们是内部执行/计费链路的一等公民。
    SELECT count(*) INTO broken
    FROM gateway.model model
    WHERE model.code IN ('video-basic', 'video-budget', 'video-standard', 'video-pro', 'video-max')
      AND NOT model.enabled;
    IF broken > 0 THEN
        RAISE EXCEPTION 'internal_video_alias_disabled:count=%', broken;
    END IF;
END $$;
