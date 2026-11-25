-- V108：两条音频 ASR 别名补上百炼路由 —— 不再单点依赖天翼
--
-- 版本号说明：本条迁移原先写成 V106，与已在生产应用过的
-- `V106__backfill_audio_capability_for_existing_video_packages.sql`（把音频能力
-- 回填给老用户的视频套餐实例）**撞号**。同一个目录里两条同号迁移会让 Flyway
-- 直接拒绝启动，而那条 V106 已经写进 gateway.flyway_schema_history（校验和
-- f81f5c5b…），改名会让校验失败——因此**改的是本条**，它尚未被任何环境应用过。
-- 106 与 107 都留给回填那条的号段，本条取 108。
--
-- 背景：audio-asr-basic 与 audio-asr-subtitle 原先各自只挂在天翼渠道
-- （tianyi-audio-primary）上。2026-10-09 实测发现两件事：
--
--   1. 天翼账号**未开通** qwen-audio-3.0-asr-flash-filetrans。用该渠道自己的密钥
--      拉 /v1/models 只看到 5 个模型，没有这个 filetrans 模型；失败原因码是
--      provider_rejected_transcription:model_not_activated。字幕别名因此完全不可用。
--   2. 公网百炼（bailian-audio-primary）**两个模型都有**：同步的
--      qwen-audio-3.0-asr-flash 与异步的 qwen-audio-3.0-asr-flash-filetrans
--      都返回 200（同一把渠道密钥直连厂商实测，对照一个不存在的名字得到
--      "Model not exist."）。适配器本来就认识这两个名字，无需改代码。
--
-- 于是给两个别名各补一条百炼路由：任一厂商可用，别名就可用。
--
-- **为什么 priority_override = 200（而不是留空）**：
-- 音频候选查询按 COALESCE(mapping.priority_override, channel.priority) DESC 排序，
-- 末尾还有 random() 打破平局；而 AudioGenerationService.transcribeNew 只取
-- **第一条**候选、**不做失败切换**（.next()）。天翼渠道 priority = 100，
-- 两条同为 100 时一次调用会随机落到当前不可用的那条上——那正是"配了两条、
-- 一半请求失败"的成因。给百炼这条更高的优先级，结果才是确定的。
--
-- 天翼路由**保持原样不动**：厂商开通后若要改回，去掉这个 override 即可
-- （届时两条都可用，随机分流也没有问题）。
--
-- 幂等：ON CONFLICT (channel_id, model_id) DO UPDATE，可重复执行。

INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities,
    priority_override, weight_override)
SELECT gen_random_uuid(), channel.id, model.id, route.upstream_model, true,
       '["AUDIO_TRANSCRIBE"]'::jsonb, 200, 100
FROM (VALUES
        ('audio-asr-basic', 'qwen-audio-3.0-asr-flash'),
        ('audio-asr-subtitle', 'qwen-audio-3.0-asr-flash-filetrans')
     ) AS route(alias, upstream_model)
JOIN gateway.model model ON model.code = route.alias
CROSS JOIN gateway.channel channel
WHERE channel.code = 'bailian-audio-primary'
ON CONFLICT (channel_id, model_id) DO UPDATE
SET upstream_model = EXCLUDED.upstream_model,
    enabled = EXCLUDED.enabled,
    capabilities = EXCLUDED.capabilities,
    priority_override = EXCLUDED.priority_override,
    weight_override = EXCLUDED.weight_override,
    updated_at = now();

-- ---------------------------------------------------------------------------
-- 断言
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    broken integer;
BEGIN
    -- 1. 两条别名各有且只有一条百炼转写路由，模型名正确、能力正确、优先级高于天翼
    SELECT count(*) INTO broken
    FROM (VALUES
            ('audio-asr-basic', 'qwen-audio-3.0-asr-flash'),
            ('audio-asr-subtitle', 'qwen-audio-3.0-asr-flash-filetrans')
         ) AS expected(alias, upstream_model)
    WHERE NOT EXISTS (
        SELECT 1
        FROM gateway.channel_model mapping
        JOIN gateway.model model ON model.id = mapping.model_id
        JOIN gateway.channel channel ON channel.id = mapping.channel_id
        WHERE model.code = expected.alias
          AND channel.code = 'bailian-audio-primary'
          AND mapping.upstream_model = expected.upstream_model
          AND mapping.enabled
          AND mapping.capabilities @> '["AUDIO_TRANSCRIBE"]'::jsonb
          AND COALESCE(mapping.priority_override, channel.priority) > (
              SELECT COALESCE(other.priority_override, other_channel.priority)
              FROM gateway.channel_model other
              JOIN gateway.channel other_channel ON other_channel.id = other.channel_id
              WHERE other.model_id = model.id
                AND other_channel.code = 'tianyi-audio-primary'));
    IF broken > 0 THEN
        RAISE EXCEPTION 'bailian_asr_route_missing_or_not_prioritised:count=%', broken;
    END IF;

    -- 2. 天翼那两条路由必须还在（本迁移只做加法；删掉它们会让厂商开通后无路可走）
    SELECT count(*) INTO broken
    FROM (VALUES ('audio-asr-basic'), ('audio-asr-subtitle')) AS alias(code)
    WHERE NOT EXISTS (
        SELECT 1
        FROM gateway.channel_model mapping
        JOIN gateway.model model ON model.id = mapping.model_id
        JOIN gateway.channel channel ON channel.id = mapping.channel_id
        WHERE model.code = alias.code AND channel.code = 'tianyi-audio-primary');
    IF broken > 0 THEN
        RAISE EXCEPTION 'tianyi_asr_route_was_removed:count=%', broken;
    END IF;

    -- 3. 两个别名的费率行仍然在（路由变动不该影响定价）
    SELECT count(*) INTO broken
    FROM (VALUES ('audio-asr-basic'), ('audio-asr-subtitle')) AS alias(code)
    WHERE NOT EXISTS (
        SELECT 1 FROM gateway.model_usage_price price
        WHERE price.model_code = alias.code
          AND price.usage_dimension = 'AUDIO_SECOND'
          AND price.effective_to IS NULL);
    IF broken > 0 THEN
        RAISE EXCEPTION 'asr_alias_pricing_row_missing:count=%', broken;
    END IF;
END $$;
