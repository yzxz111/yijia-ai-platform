-- V105: 修正配音合成的上游模型名 —— 公网百炼的合法 id 是 qwen3-tts-flash
--
-- 背景：V97 把 audio-tts-basic 映射到 qwen-audio-3.0-tts-flash。那串名字来自需求
-- 文本，也是**天翼网关**的命名（同族的 qwen-audio-3.0-asr-flash 在天翼
-- ai.ctaigw.cn 上确实可用，见 V96）。但 audio-tts-basic 挂的是**公网百炼**
-- （bailian-audio-primary，base_url https://dashscope.aliyuncs.com），
-- 在那里这个名字不存在。
--
-- 线上实测（2026-10-09，用渠道自己的密钥直连厂商，不经过网关）：
--   qwen-audio-3.0-tts-flash -> 400 InvalidParameter（url error）
--   qwen-audio-3.1-tts-flash -> 400 InvalidParameter
--   qwen3-tts-flash          -> 200，返回 output.audio.url
--   qwen-tts                 -> 200
--   （对照：把模型名换成不存在的词会得到 "Model not exist."，
--     因此上面两条 400 是"名字不存在"这一类，而不是请求体形态问题）
--
-- 症状经由网关表现为 502 provider_error；任务与预留都被正确释放
-- （billed_seconds = 0，额度不损失），所以它不会造成计费错误——
-- 只会让这条别名看起来"填了 key 却不能用"。
--
-- 适配器**早已支持**这个名字：BailianAudioSynthesisAdapter.MODELS 含
-- qwen3-tts-flash（同时保留天翼那两个名字，因为同一条通道将来可能改指天翼）。
-- 因此本迁移只改映射，不动任何 Java 代码。
--
-- 为什么不改 V97：它已在所有环境应用过，改动会让 Flyway 校验和失败、网关拒绝启动。
-- 新环境会先执行 V97（写入旧名字）再执行本迁移（纠正），最终状态一致。

UPDATE gateway.channel_model mapping
SET upstream_model = 'qwen3-tts-flash', updated_at = now()
FROM gateway.model model
WHERE mapping.model_id = model.id
  AND model.code = 'audio-tts-basic'
  AND mapping.upstream_model = 'qwen-audio-3.0-tts-flash';

-- ---------------------------------------------------------------------------
-- 断言：改正了、没有遗漏、也没有顺手改坏别的东西
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken integer;
BEGIN
    -- 1. audio-tts-basic 现在指向公网百炼上真实存在的模型名
    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.model model ON model.id = mapping.model_id
    WHERE model.code = 'audio-tts-basic'
      AND mapping.upstream_model <> 'qwen3-tts-flash';
    IF broken > 0 THEN
        RAISE EXCEPTION 'tts_upstream_model_not_corrected:rows=%', broken;
    END IF;

    -- 2. 全库不再有任何一行还指着那个不存在的名字
    --    （只查这一条别名：别的别名合法地使用天翼命名，不在本次范围内）
    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.model model ON model.id = mapping.model_id
    WHERE model.code = 'audio-tts-basic'
      AND mapping.upstream_model = 'qwen-audio-3.0-tts-flash';
    IF broken > 0 THEN
        RAISE EXCEPTION 'legacy_tts_model_name_still_present:rows=%', broken;
    END IF;

    -- 3. 这条别名仍然只挂在音频渠道上，且能力没被顺手改掉
    --    （一次只该改一个字段；能力或渠道变了说明 UPDATE 写宽了）
    SELECT count(*) INTO broken
    FROM gateway.channel_model mapping
    JOIN gateway.model model ON model.id = mapping.model_id
    JOIN gateway.channel channel ON channel.id = mapping.channel_id
    WHERE model.code = 'audio-tts-basic'
      AND (channel.code <> 'bailian-audio-primary'
           OR NOT (mapping.capabilities @> '["AUDIO_SYNTHESIS"]'::jsonb));
    IF broken > 0 THEN
        RAISE EXCEPTION 'tts_route_shape_changed:rows=%', broken;
    END IF;

    -- 4. 费率行必须仍然在（改映射不该影响定价；缺了它这条别名会无法按秒计费）
    SELECT count(*) INTO broken
    FROM gateway.model_usage_price price
    WHERE price.model_code = 'audio-tts-basic'
      AND price.usage_dimension = 'AUDIO_SECOND'
      AND price.effective_to IS NULL;
    IF broken = 0 THEN
        RAISE EXCEPTION 'tts_pricing_row_missing:code=audio-tts-basic';
    END IF;
END $$;
