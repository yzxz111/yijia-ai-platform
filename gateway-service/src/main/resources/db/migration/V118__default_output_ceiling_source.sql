-- ---------------------------------------------------------------------------
-- 输出上限的来源多出第五种：default
--
-- 前情：V116 给 usage_log 加了 ceiling_source，词表是四层 min() 的四个输入
-- （client / model / package / system）。当时"客户端没传 max_tokens"意味着"上限就是
-- 套餐或系统给出的那个数"，因此它总能落到这四者之一。
--
-- 2026-10-19 起不再如此：系统上限抬到 100 万（ReservationCeiling.SYSTEM_MAX_OUTPUT_TOKENS），
-- 而"客户端没传"的请求改为按 8 192 的默认值（DEFAULT_MAX_OUTPUT_TOKENS）去请求与预留。
-- 这个 8 192 既不是客户端要的、也不是任何一层的能力上限——把它记成 system 会让运维
-- 去查一个根本不存在的"系统上限 8192"，记成 client 则是替客户端编造了一个它没发过的参数。
-- 因此词表必须有一个诚实的第五项。
--
-- 这个迁移只放宽 CHECK，不改任何已有数据：历史行的取值仍然合法，新值只是在今后出现。
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.usage_log
    DROP CONSTRAINT IF EXISTS ck_usage_log_ceiling_source;

ALTER TABLE gateway.usage_log
    ADD CONSTRAINT ck_usage_log_ceiling_source
        CHECK (ceiling_source IS NULL
               OR ceiling_source IN ('client', 'model', 'package', 'system', 'default'));

COMMENT ON COLUMN gateway.usage_log.ceiling_source IS
    'Which of the five inputs produced effective_max_output_tokens: client max_tokens, '
    'the model cap, the plan''s sold cap, the gateway system cap (1,000,000), or the '
    'gateway default (8,192) used when the client asked for no limit at all. Without it '
    'the number alone cannot answer "is 4096 a package limit or a client one?"';

COMMENT ON COLUMN gateway.usage_log.effective_max_output_tokens IS
    'The output ceiling this request was actually admitted against: '
    'min(client max_tokens or the 8,192 default, model cap, package cap, system cap). '
    'This is the number the provider was asked for, not the model''s nominal maximum.';
