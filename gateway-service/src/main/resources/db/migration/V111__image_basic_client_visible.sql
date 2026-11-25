-- V111: 让生图别名重新出现在客户端的「套餐模型」列表里
--
-- 背景：V103 的意图是「通用文本目录只保留 chat-standard / chat-deep」，它的注释
-- 明确写着视频/音频/图片模型「靠各自的能力与档位表进入专用目录，**不用
-- client_visible 表达**」。但那条 UPDATE 写的是「不在白名单里就不可见」，而白名单
-- 只列了 chat-* / video-* / audio-*，**漏了 image-basic**。
--
-- 后果：image-basic 被置成 client_visible = false，而
-- ManagedApiKeyRepository.findClientVisibleModels()（桌面端 bootstrap 的
-- allowedModels 的唯一来源）正好按这一列过滤：
--
--     SELECT alias.alias, model.capabilities::text FROM gateway.model_alias alias
--     JOIN gateway.model model ON model.id = alias.model_id
--     WHERE model.client_visible AND model.enabled
--
-- 于是 image-basic 在**任何**维度下都不会出现在客户端的「套餐模型」下拉里。持有
-- IMAGE_BASIC 套餐的账号进图片页只能看到「当前套餐暂无可用的图片模型」——而网关
-- 侧的权益、费率、档位、路由全都是好的（线上实测 2026-10-09）。
--
-- 为什么置回 true 不会污染对话目录（/v1/models）：该目录由
-- ModelCatalogRepository.findEntitledModels 决定，它先要求
-- capabilities @> '["TEXT"]'，再排除 VIDEO 与 IMAGE_GEN，并且排除任何在
-- image_alias_tier 里登记过的别名——image-basic 三条全中。也就是说 client_visible
-- 对它**只是第二道冗余的锁**，而它同时是客户端图片入口的**唯一**开关。
--
-- 为什么置回 true 不会污染别的维度：ManagedApiKeyService.relevantTo 里
-- IMAGE 维度要求 IMAGE_GEN，VIDEO_SECOND 要求 VIDEO，AUDIO_SECOND 要求音频能力，
-- TOKEN（默认）要求 TEXT **且不含 VIDEO、不含 IMAGE_GEN**——四条里只有 IMAGE 命中它。

UPDATE gateway.model
SET client_visible = true, updated_at = now()
WHERE code = 'image-basic';

-- ---------------------------------------------------------------------------
-- 断言
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    image_list text;
BEGIN
    -- 1) 生图别名必须重新可见：它是客户端图片入口的唯一开关
    IF (SELECT client_visible FROM gateway.model WHERE code = 'image-basic')
       IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'image_basic_must_be_client_visible';
    END IF;

    -- 2) 图片维度（IMAGE）的客户端下拉必须恰好是 image-basic（它只有一个别名）
    SELECT string_agg(code, ', ' ORDER BY code) INTO image_list
      FROM gateway.model
     WHERE client_visible AND enabled
       AND capabilities @> '["IMAGE_GEN"]'::jsonb;
    IF image_list IS DISTINCT FROM 'image-basic' THEN
        RAISE EXCEPTION 'image_dimension_catalog_must_be_exactly_image_basic:got=%',
            coalesce(image_list, '<null>');
    END IF;

    -- 3) 它**不得**因为这次改动漏进文本下拉。这条断言刻意只问 image-basic 自己，
    --    而不是重新断言「文本目录恰好两个」——后者会被与本改动无关的历史数据
    --    （线上确实存在一行 code 为「视频模型」的遗留模型）打红，从而让迁移
    --    在生产上直接失败、网关拒绝启动。
    IF EXISTS (
        SELECT 1 FROM gateway.model
         WHERE code = 'image-basic'
           AND client_visible AND enabled
           AND capabilities @> '["TEXT"]'::jsonb
           AND NOT (capabilities @> '["IMAGE_GEN"]'::jsonb)) THEN
        RAISE EXCEPTION 'image_basic_leaked_into_text_catalog';
    END IF;
END $$;
