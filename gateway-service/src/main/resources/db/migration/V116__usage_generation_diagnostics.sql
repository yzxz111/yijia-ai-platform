-- ---------------------------------------------------------------------------
-- T5 / T3 of 中台任务链路中断排查与修复要求 (2026-10-15): make the计量结果 and the
-- 生成终态 separable, and record WHICH ceiling actually bound the request.
--
-- Why this is needed, in the client's own words: a request truncated at the
-- package's 4096 ceiling is stored today as
--
--     outcome = 'SUCCEEDED', http_status = 200, error_summary = NULL
--
-- so neither the usage query nor the CSV export can tell it apart from a request
-- that finished naturally. The desktop recorded `finish_reason=length` and
-- `model_output_truncated` for the same request; the platform recorded nothing.
-- Whoever reads the report concludes the task completed.
--
-- `http_status` already carries the transport outcome and is NOT touched --
-- existing reports keep their meaning. These columns are additive and nullable,
-- so every historical row reads as "unknown" rather than as a guessed value.
--
-- The five values requested by the document (五.日志和结算 / 四.证据表):
--   * 生成状态 completed / failed / truncated  -- 终态，与计量分开表达
--   * finish_reason                            -- 上游或网关给出的结束原因
--   * 有效输出上限 + 它的来源                  -- 四层 min() 里到底哪一层生效
--   * 尝试次数                                 -- 重试与切换渠道的痕迹
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.usage_log
    ADD COLUMN generation_status varchar(20),
    ADD COLUMN finish_reason varchar(32),
    ADD COLUMN effective_max_output_tokens bigint,
    ADD COLUMN ceiling_source varchar(20),
    ADD COLUMN attempt_count integer;

-- 词表用 CHECK 钉住，而不是留给写入方自觉：这三个值是**给运维和导出看的**，
-- 一个拼错的值（"truncate" / "TRUNCATED"）会让筛选悄悄少掉几行，而报表不会报错。
-- 允许 NULL：历史行没有这个信息，把它们标成某个具体状态才是撒谎。
ALTER TABLE gateway.usage_log
    ADD CONSTRAINT ck_usage_log_generation_status
        CHECK (generation_status IS NULL
               OR generation_status IN ('completed', 'failed', 'truncated'));

-- 上限来源就是 ReservationCeiling.effectiveMaxOutputTokens 的那四个输入。
-- 四层分别是：客户端 max_tokens/max_completion_tokens、模型声明的上限、
-- 套餐方案出售的上限、网关的系统上限。
ALTER TABLE gateway.usage_log
    ADD CONSTRAINT ck_usage_log_ceiling_source
        CHECK (ceiling_source IS NULL
               OR ceiling_source IN ('client', 'model', 'package', 'system'));

-- 尝试次数是"这一行记录了几次供应商调用"，最小为 1（真实的调用）或 0（从未发出）。
-- 负数没有任何含义，而一个负的尝试次数会让"重试了几次"的统计整体偏移。
ALTER TABLE gateway.usage_log
    ADD CONSTRAINT ck_usage_log_attempt_count
        CHECK (attempt_count IS NULL OR attempt_count >= 0);

-- 一致性：被截断的行必须写明结束原因是 length。
--
-- 这两列来自两条不同的通路（生成状态来自网关自己的截断判定，finish_reason 来自
-- 上游那一帧），所以它们有可能说法不一。不一致的记录比没有记录更糟：运维会照着一个
-- 自相矛盾的行去查上游。这里把"截断必有 length"钉成一条不变量。
-- 反向不成立——上游自己返回 length 时网关并没有截断任何东西，那也是合法的截断。
ALTER TABLE gateway.usage_log
    ADD CONSTRAINT ck_usage_log_truncated_has_length
        CHECK (generation_status IS DISTINCT FROM 'truncated'
               OR finish_reason IS NULL
               OR finish_reason = 'length');

COMMENT ON COLUMN gateway.usage_log.generation_status IS
    'completed / failed / truncated. Deliberately separate from outcome (which '
    'is the metering disposition) and from http_status (the transport result): a '
    'request cut off by the output ceiling is metered and billed normally, yet it '
    'is not a completed generation. NULL for rows written before 2026-10-15.';
COMMENT ON COLUMN gateway.usage_log.finish_reason IS
    'The end reason as reported by the provider (stop/length/tool_calls/...) or '
    'by the gateway''s own output-cap cut (length). NULL when never observed.';
COMMENT ON COLUMN gateway.usage_log.effective_max_output_tokens IS
    'The output ceiling this request was actually admitted against: '
    'min(client max_tokens, model cap, package cap, system cap). This is the '
    'number the provider was asked for, not the model''s nominal maximum.';
COMMENT ON COLUMN gateway.usage_log.ceiling_source IS
    'Which of the four inputs produced effective_max_output_tokens. Without it '
    'the number alone cannot answer "is 4096 a package limit or a client one?"';
COMMENT ON COLUMN gateway.usage_log.attempt_count IS
    'How many provider calls this request produced, including retries and '
    'channel failover. 1 for an ordinary request, 0 when none was ever issued.';
