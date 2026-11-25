-- 客户端目录只保留「能力专属入口」，把通用文本入口收敛到两个通道。
--
-- 背景：V102 建了两个文本通道（chat-standard「普通」/ chat-deep「深度」）并把四个
-- 纯文本别名标记为不可见。但 `vision-*` / `file-basic` / `multimodal-basic` /
-- `image-basic` 的能力里也带 `TEXT`，于是仍然出现在 `/v1/models` 里——客户端因此
-- 在文本选择器中看到 4~5 个入口，而不是要求的那两个。
--
-- 本迁移把这些**能力专属**别名也从通用目录里收起：
--
--   * 读图：两个通道本身路由到 Qwen2.5-VL-72B-Instruct（视觉模型），图片输入由
--     它们承担，不需要 vision-* 作为客户端的图片入口；
--   * 传文件：额度按套餐判定（UploadEntitlementService.packageEntitlement），
--     与模型可见性无关，收起 file-basic/multimodal-basic 不影响上传能力；
--   * 生图 image-basic、天翼视觉 vision-tianyi：同样不再出现在通用目录。
--
-- **可调用性完全不变**：这些别名仍在 `gateway.package_instance.allowed_models` 里，
-- 鉴权与路由只认那份清单，因此第三方兼容客户端按名字调用照旧；控制台的模型管理
-- 走 GatewayAdminRepository 的独立查询，不受 client_visible 影响。
--
-- 判定写成"不在白名单里就不可见"，而不是逐个点名：将来新增的**通用文本**别名会
-- 默认被收起（这正是 V102 的意图），而新增的视频/音频/图片模型不受影响——它们靠
-- 各自的能力与档位表进入专用目录，不用 client_visible 表达。

UPDATE gateway.model
SET client_visible = false, updated_at = now()
WHERE client_visible
  AND code NOT IN (
        'chat-standard', 'chat-deep',
        'video-basic', 'video-budget', 'video-pro', 'video-standard', 'video-max', 'video-minimax',
        'audio-asr-basic', 'audio-tts-basic', 'audio-asr-subtitle', 'audio-retalk');

-- ---------------------------------------------------------------------------
-- 断言
--
-- 通用文本目录必须恰好是那两个通道。这条断言的价值在于：它是"客户端看到几个入口"
-- 这件事在数据库里的唯一事实，漏掉任何一个别名都会让客户端多看到一个模型名。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    visible_text text;
    visible_count integer;
    extra_guards text := '';
BEGIN
    -- 档位表的排除条件用**动态 SQL** 拼：image_alias_tier / audio_alias_tier 由更早的
    -- 迁移建立，在只回放到早期版本的库（例如用生产快照演练）里可能还不存在。
    -- 用 to_regclass 做静态守卫没用——PostgreSQL 在解析阶段就要求 FROM 里的表存在，
    -- 不会因为条件为假而短路。拼进字符串后，不存在的表根本不会出现在被执行的语句里。
    IF to_regclass('gateway.image_alias_tier') IS NOT NULL THEN
        extra_guards := extra_guards || ' AND NOT EXISTS (SELECT 1 FROM gateway.image_alias_tier t WHERE t.model_code = m.code)';
    END IF;
    IF to_regclass('gateway.audio_alias_tier') IS NOT NULL THEN
        extra_guards := extra_guards || ' AND NOT EXISTS (SELECT 1 FROM gateway.audio_alias_tier t WHERE t.model_code = m.code)';
    END IF;

    EXECUTE
        'SELECT count(*), string_agg(m.code, '', '' ORDER BY m.code) '
        'FROM gateway.model m '
        'WHERE m.client_visible AND m.enabled '
        '  AND m.capabilities @> ''["TEXT"]''::jsonb '
        '  AND NOT (m.capabilities @> ''["VIDEO"]''::jsonb) '
        '  AND NOT (m.capabilities @> ''["IMAGE_GEN"]''::jsonb) '
        '  AND NOT (m.capabilities @> ''["AUDIO_TRANSCRIBE"]''::jsonb) '
        '  AND NOT (m.capabilities @> ''["AUDIO_SYNTHESIS"]''::jsonb) '
        '  AND NOT (m.capabilities @> ''["LIP_SYNC"]''::jsonb) '
        '  AND NOT EXISTS (SELECT 1 FROM gateway.model_usage_price p '
        '                  WHERE p.model_code = m.code AND p.usage_dimension = ''VIDEO_SECOND'' '
        '                    AND p.effective_to IS NULL) '
        '  AND NOT EXISTS (SELECT 1 FROM gateway.video_alias_tier t WHERE t.model_code = m.code)'
        || extra_guards
        INTO visible_count, visible_text;

    IF visible_count <> 2
       OR visible_text IS DISTINCT FROM 'chat-deep, chat-standard' THEN
        RAISE EXCEPTION 'client_text_catalog_must_be_exactly_two_channels:count=% names=%',
            visible_count, visible_text;
    END IF;
END $$;
