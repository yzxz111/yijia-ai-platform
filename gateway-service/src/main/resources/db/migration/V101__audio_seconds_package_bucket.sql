-- 方案 C 第 4、5 步：网关侧的"第二份额度"——音频秒。
--
-- 平台侧 V100 已经给 platform.package_plan_version 加了 audio_seconds_quota，
-- 并且 PackageVersionService 从第 2、3 步起允许「视频秒 + 音频秒」的混合版本。
-- 但网关侧到此为止**完全没有**音频秒这个概念：
--   * gateway.package_instance 只有一份额度（total_token_quota / reserved_tokens
--     / used_tokens，单位由 usage_dimension 决定），因此一个混合套餐的音频秒
--     无处安放；
--   * QuotaService 只按 lockedPackage.usageDimension() 判断维度，于是一次 ASR
--     请求会以 usage_dimension_mismatch 被拒（正确但不可用），而如果放行，
--     它会去扣**视频秒**——这正是方案 C 存在的理由（ASR 约 5 分/次、
--     视频秒 69 分/秒，1:1 混用是三个数量级的错价）。
--
-- 本迁移只做两件结构性的、零行为变化的事：
--   1. 给 gateway.package_instance 加上第二份额度（三个列），
--   2. 让 gateway.quota_reservation 记住自己持有的是哪个单位的预留。
-- 谁扣哪一列由 QuotaService 决定（同一批改动里的 Java 部分）。
--
-- ---------------------------------------------------------------------------
-- 为什么是三个列而不是一个
-- ---------------------------------------------------------------------------
-- 只加 audio_seconds_quota 是不够的：额度必须能被**独立消耗**，否则两个桶
-- 会共用一个计数器，而"音频秒用完不得阻塞视频生成，反之亦然"这条要求
-- 就无法表达。reserved/used 与既有列同形、同单位（秒），因此
-- `audio_seconds_reserved + audio_seconds_used <= audio_seconds_quota`
-- 可以直接写成 CHECK——与 V1 给主桶写的
-- `reserved_tokens + used_tokens <= total_token_quota` 完全对称。
--
-- ---------------------------------------------------------------------------
-- 为什么只有 VIDEO_SECOND 主维度可以携带它
-- ---------------------------------------------------------------------------
-- 平台的 V100 注释写着：这一列是"一个同时卖视频秒与音频秒的版本"的第二份额度。
-- 一个**纯音频**版本（usage_dimension = AUDIO_SECOND）不需要它，也不该用它：
-- 那种版本的主桶本身就是音频秒（total_token_quota 的含义由 usage_dimension
-- 决定，V42:16-22），再填一份会把"这次请求扣哪一列"变成两个都说得通的答案。
-- 平台侧 V101 收紧同一条规则，网关侧在此处拒绝，这样歧义在写入的那一刻就被
-- 拒绝，而不是等到一次结算读错了桶。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
--       （见 "Decided: a video package carries video seconds **and** audio seconds"）
-- 参照：gateway V40/V41/V42（秒维度与 package_instance 的两个桶）、
--      V96（AUDIO_SECOND 的七处词表）、平台 V100（版本级的那一列）

-- ---------------------------------------------------------------------------
-- 1. 实例级第二份额度
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.package_instance
    ADD COLUMN audio_seconds_quota bigint,
    ADD COLUMN audio_seconds_reserved bigint NOT NULL DEFAULT 0,
    ADD COLUMN audio_seconds_used bigint NOT NULL DEFAULT 0;

-- 单独一条约束，而不是重述 V1 的 ck_package_instance_values：
-- 一条"加东西"的迁移重述旧约束时很容易顺手丢掉其中一个分支，
-- 而丢掉的那个分支不会让任何东西失败（V96:162-169 记录过同形的近失）。
-- 新约束只讲新列，因此没有任何可丢的东西。
--
-- 写成 CASE 而不是两个 OR 起来的分支，是为了**不把 NULL 漏进来**：
-- 第一种写法（`quota IS NULL AND res = 0 AND used = 0 OR quota > 0 AND ...`）
-- 在 quota 为 NULL、used 为正时求值为 `FALSE OR NULL`，而 CHECK 只在结果为
-- FALSE 时拒绝——于是"没有额度却已经有消耗"这个状态被静默地放行了。
-- 这不是理论问题：本迁移的第一次运行就是这样，是下面那条反向探针把它抓出来的
-- （探针当时还丢掉了 NOT NULL，于是 NULL 从另一个方向进来）。真实表上两个
-- 计数器都是 NOT NULL，因此 ELSE 分支永远不可能是 NULL，而这个 CASE 让
-- 这一点不再重要。
ALTER TABLE gateway.package_instance
    ADD CONSTRAINT ck_package_instance_audio_seconds CHECK (
        audio_seconds_reserved >= 0
        AND audio_seconds_used >= 0
        AND CASE WHEN audio_seconds_quota IS NULL
                 THEN audio_seconds_reserved = 0 AND audio_seconds_used = 0
                 ELSE audio_seconds_quota > 0
                      AND usage_dimension = 'VIDEO_SECOND'
                      AND audio_seconds_reserved + audio_seconds_used
                          <= audio_seconds_quota
            END);

COMMENT ON COLUMN gateway.package_instance.audio_seconds_quota IS
    'Optional SECOND quota for a video package that also sells audio seconds '
    '(方案 C). NULL = this instance sells no audio. Only a VIDEO_SECOND instance may '
    'set it: a pure AUDIO_SECOND instance already carries its audio seconds in '
    'total_token_quota, and setting both would make "which column does this request '
    'debit" have two defensible answers.';
COMMENT ON COLUMN gateway.package_instance.audio_seconds_reserved IS
    'In-flight AUDIO_SECOND units. A separate counter from reserved_tokens, because '
    'an ASR second must never consume a video second: the two are three orders of '
    'magnitude apart in cost.';
COMMENT ON COLUMN gateway.package_instance.audio_seconds_used IS
    'Consumed AUDIO_SECOND units. Exhaustion of this bucket must not end the instance '
    'while the primary bucket still has room, and vice versa.';

-- ---------------------------------------------------------------------------
-- 2. 预留记住自己的单位
-- ---------------------------------------------------------------------------
-- 在此之前 gateway.quota_reservation 没有这一列：结算路径靠一个相关子查询
-- `(SELECT p.usage_dimension FROM gateway.package_instance p ...)` 现算维度。
-- 那在只有一个桶时是对的，在两桶之后不再够用——一次 ASR 预留的维度既不是
-- 实例的主维度（视频秒），也无法从预留行反推。
--
-- 因此这一列记录"这笔预留持有的是什么单位"，由预留路径写入、由结算与释放
-- 读取。它同时消掉了一个静默默认值：R2DBC 的映射器在列缺失时返回
-- 'TOKEN'，而 TOKEN 与 VIDEO_SECOND 走**同一个主桶**，所以那个默认值曾经
-- 无害；音频桶出现后，同一个默认值会让一次 ASR 结算去扣视频秒。
-- 这类错误不会报错，只会算错钱——正是它必须被存下来而不是被推导的原因。
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.quota_reservation
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN';

-- 回填：历史行按它所属实例的主维度取值，这与它们当时的实际计费口径一致。
UPDATE gateway.quota_reservation reservation
SET usage_dimension = package.usage_dimension
FROM gateway.package_instance package
WHERE package.id = reservation.package_instance_id;

ALTER TABLE gateway.quota_reservation
    ADD CONSTRAINT ck_reservation_usage_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

COMMENT ON COLUMN gateway.quota_reservation.usage_dimension IS
    'Unit this reservation holds: TOKEN / VIDEO_SECOND / IMAGE / AUDIO_SECOND. '
    'Written by the reservation path and read by settlement and release, so the '
    'bucket a request is charged against does not have to be re-derived from a '
    'package that may sell two of them (方案 C). Wallet-only reservations are TOKEN.';

-- ---------------------------------------------------------------------------
-- 3. 权益维度触发器：视频秒与音频秒是**两个独立的池**，因此可以共存
-- ---------------------------------------------------------------------------
-- V40:360-405 的规则是「按秒的别名不得与其它别名共享一个套餐，除非声明
-- {"mixedPools": true}」，而它当时只会把 VIDEO_SECOND 别名数进"按秒"那一侧。
-- 于是方案 C 的混合版本（2 个视频别名 + 1 个音频别名）会被它当成
-- "视频 + 别的"，在发放的那一刻以 video_model_in_token_package 失败——而
-- 那时候客户已经付过款了。
--
-- 改法**只动计数集合的一个成员**：把"按秒"从 VIDEO_SECOND 扩成
-- VIDEO_SECOND 或 AUDIO_SECOND。这样：
--   * 视频 + 音频 → 总数 == 按秒数 → 放行（它们各自有一列，没有共享的池）；
--   * 视频 + token  → 仍然抛错（V40 的原意，也是本触发器存在的理由）；
--   * 视频 + 音频 + token → 仍然抛错。
-- 刻意**不**把 audio + token 也纳入：那种组合平台侧 resolveDimension 已经拒绝，
-- 而把触发器的判据从"有视频别名"改成"有按秒别名"会同时改变一个今天
-- 无人守卫的组合的行为，那是这次改动不需要承担的判断。
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION gateway.assert_package_entitlement_dimension()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    all_models text[];
    video_models text[];
    second_models text[];
BEGIN
    IF jsonb_typeof(NEW.allowed_models) <> 'array' THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE(array_agg(permission ->> 'modelCode'), '{}'::text[])
    INTO all_models
    FROM jsonb_array_elements(NEW.allowed_models) AS permission
    WHERE permission ->> 'modelCode' IS NOT NULL;

    -- Only aliases whose CURRENT rate card row is a VIDEO_SECOND dimension.
    SELECT COALESCE(array_agg(permission ->> 'modelCode'), '{}'::text[])
    INTO video_models
    FROM jsonb_array_elements(NEW.allowed_models) AS permission
    WHERE EXISTS (
        SELECT 1
        FROM gateway.model_usage_price price
        WHERE price.model_code = permission ->> 'modelCode'
          AND price.usage_dimension = 'VIDEO_SECOND'
          AND price.effective_to IS NULL);

    -- 方案 C：音频秒与视频秒各自持有独立的额度列（本迁移第 1 段），
    -- 因此两者并存**不构成**共享的池。
    SELECT COALESCE(array_agg(permission ->> 'modelCode'), '{}'::text[])
    INTO second_models
    FROM jsonb_array_elements(NEW.allowed_models) AS permission
    WHERE EXISTS (
        SELECT 1
        FROM gateway.model_usage_price price
        WHERE price.model_code = permission ->> 'modelCode'
          AND price.usage_dimension IN ('VIDEO_SECOND', 'AUDIO_SECOND')
          AND price.effective_to IS NULL);

    -- A video alias may not share a package with token aliases unless the
    -- package explicitly declares two independent pools.
    IF array_length(video_models, 1) > 0
            AND array_length(all_models, 1) > array_length(second_models, 1)
            AND NOT (NEW.allowed_models @> '[{"mixedPools": true}]'::jsonb) THEN
        RAISE EXCEPTION 'video_model_in_token_package';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION gateway.assert_package_entitlement_dimension() IS
    'V40 entitlement guard, widened by V101 for 方案 C: a package may carry '
    'VIDEO_SECOND and AUDIO_SECOND aliases together (each has its own quota column), '
    'but a video alias still may not share a package with a structurally different '
    'pool (TOKEN/IMAGE) unless it declares {"mixedPools": true}.';

-- ---------------------------------------------------------------------------
-- 4. 断言
-- ---------------------------------------------------------------------------
-- 两部分，缺一不可：
--   (a) 词表/形状类断言用**探针表**（LIKE ... INCLUDING CONSTRAINTS）证明约束
--       真的接受合法的行、真的拒绝非法行——只看 pg_get_constraintdef 的文本
--       只能证明我写下了那个词，证明不了它落在哪一侧的分支里（V96:350-364 的
--       教训）。
--   (b) 触发器断言用一个真实的 package_instance 行，因为
--       `BEFORE INSERT ... FOR EACH ROW` 只有在真实 DML 上才会跑。
--       正反两个方向都要：混合版本必须被接受，视频 + token 必须仍然被拒绝。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    definition     text;
    probe_table    text := 'probe_audio_bucket';
    probe_constraint text;
    probe_column   text;
    column_state   text;
    column_type    text;
    default_value  text;
    rejected       boolean;
    mismatched     integer;
    second_count   integer;
    probe_id       uuid := gen_random_uuid();
BEGIN
    -- 4.1 三个列存在、类型正确、可空性正确
    SELECT count(*) INTO second_count
    FROM information_schema.columns
    WHERE table_schema = 'gateway' AND table_name = 'package_instance'
      AND column_name IN ('audio_seconds_quota', 'audio_seconds_reserved', 'audio_seconds_used');
    IF second_count <> 3 THEN
        RAISE EXCEPTION 'the_audio_bucket_columns_are_missing:found=%', second_count;
    END IF;

    SELECT is_nullable, data_type INTO column_state, column_type
    FROM information_schema.columns
    WHERE table_schema = 'gateway' AND table_name = 'package_instance'
      AND column_name = 'audio_seconds_quota';
    IF column_state <> 'YES' OR column_type <> 'bigint' THEN
        RAISE EXCEPTION
            'audio_seconds_quota_must_be_a_nullable_bigint:nullable=%:type=%',
            column_state, column_type;
    END IF;

    -- 两个计数器必须是 NOT NULL DEFAULT 0：可空的话，一个"从未用过音频"的
    -- 实例会带着 NULL 走进算术，而 NULL 在 CHECK 里是**通过**，
    -- 于是约束会静默地不再生效。
    FOR probe_column IN SELECT unnest(ARRAY['audio_seconds_reserved', 'audio_seconds_used'])
    LOOP
        SELECT information_schema.columns.is_nullable,
               information_schema.columns.column_default
        INTO column_state, default_value
        FROM information_schema.columns
        WHERE information_schema.columns.table_schema = 'gateway'
          AND information_schema.columns.table_name = 'package_instance'
          AND information_schema.columns.column_name = probe_column;

        IF column_state IS DISTINCT FROM 'NO' THEN
            RAISE EXCEPTION 'the_audio_counters_must_be_not_null:column=%:nullable=%',
                probe_column, column_state;
        END IF;
        IF default_value IS DISTINCT FROM '0' THEN
            RAISE EXCEPTION 'the_audio_counters_must_default_to_zero:column=%:default=%',
                probe_column, default_value;
        END IF;
    END LOOP;

    -- 4.2 约束存在，且它**只**允许 VIDEO_SECOND 主维度携带第二份额度
    SELECT pg_get_constraintdef(constraint_row.oid) INTO definition
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = 'gateway'
      AND table_row.relname = 'package_instance'
      AND constraint_row.conname = 'ck_package_instance_audio_seconds';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_package_instance_audio_seconds_is_missing';
    END IF;
    IF position('VIDEO_SECOND' IN definition) = 0 THEN
        RAISE EXCEPTION
            'the_audio_bucket_constraint_does_not_name_its_primary_dimension:definition=%',
            definition;
    END IF;

    -- 4.3 行为证明：克隆 package_instance、只留被测的那一条 CHECK、去掉 NOT NULL，
    --     然后逐种取值插入见证行。
    EXECUTE format('DROP TABLE IF EXISTS pg_temp.%I', probe_table);
    EXECUTE format(
            'CREATE TEMP TABLE %I (LIKE gateway.package_instance INCLUDING CONSTRAINTS) '
            'ON COMMIT DROP', probe_table);

    FOR probe_constraint IN
        SELECT constraint_row.conname
        FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = probe_table::regclass
          AND constraint_row.contype = 'c'
          AND pg_get_constraintdef(constraint_row.oid) <> definition
    LOOP
        EXECUTE format('ALTER TABLE pg_temp.%I DROP CONSTRAINT %I',
                probe_table, probe_constraint);
    END LOOP;

    FOR probe_column IN
        SELECT attribute.attname
        FROM pg_attribute attribute
        WHERE attribute.attrelid = probe_table::regclass
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
    LOOP
        EXECUTE format('ALTER TABLE pg_temp.%I ALTER COLUMN %I DROP NOT NULL',
                probe_table, probe_column);
    END LOOP;

    -- 正向：视频秒实例携带一份音频秒额度。
    --
    -- 每一条探针都**显式写出两个计数器**：这条约束的 ELSE 分支里是一次算术，
    -- 而克隆表上的 NULL 会让 `reserved + used <= quota` 求值为 NULL，
    -- 于是 CHECK 放行——一个"反向探针其实什么都没证明"的空洞。
    -- 真实表上两者都是 NOT NULL，因此这个写法只在探针里必要。
    EXECUTE format(
            'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota, '
            'audio_seconds_reserved, audio_seconds_used) '
            'VALUES (''VIDEO_SECOND'', 600, 0, 0)', probe_table);
    -- 正向：额度 + 在途 + 已用正好用满（边界必须被接受，否则合法实例会写不进去）。
    EXECUTE format(
            'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota, '
            'audio_seconds_reserved, audio_seconds_used) '
            'VALUES (''VIDEO_SECOND'', 600, 100, 500)', probe_table);
    -- 正向：完全没有音频额度（绝大多数实例的形态）。
    EXECUTE format(
            'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_reserved, '
            'audio_seconds_used) VALUES (''TOKEN'', 0, 0)', probe_table);

    -- 反向 1：纯音频主维度不得再填第二份额度（歧义）。
    rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota, '
                'audio_seconds_reserved, audio_seconds_used) '
                'VALUES (''AUDIO_SECOND'', 600, 0, 0)', probe_table);
    EXCEPTION WHEN check_violation THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION
            'a_pure_audio_instance_was_allowed_to_carry_a_second_audio_bucket:definition=%',
            definition;
    END IF;

    -- 反向 2：token 实例不得携带音频额度。
    rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota, '
                'audio_seconds_reserved, audio_seconds_used) '
                'VALUES (''TOKEN'', 600, 0, 0)', probe_table);
    EXCEPTION WHEN check_violation THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'a_token_instance_was_allowed_to_carry_audio_seconds:definition=%',
            definition;
    END IF;

    -- 反向 3：超额消耗（在途 + 已用 > 额度）必须被拒绝。
    rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota, '
                'audio_seconds_reserved, audio_seconds_used) '
                'VALUES (''VIDEO_SECOND'', 600, 0, 601)', probe_table);
    EXCEPTION WHEN check_violation THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'an_overdrawn_audio_bucket_was_allowed:definition=%', definition;
    END IF;

    -- 反向 4：没有额度却已经有消耗——一个没有桶的桶。
    rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_reserved, '
                'audio_seconds_used) VALUES (''VIDEO_SECOND'', 0, 1)', probe_table);
    EXCEPTION WHEN check_violation THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION
            'audio_consumption_without_a_quota_was_allowed:definition=%', definition;
    END IF;

    -- 反向 5：0 秒额度是"包含语音"的假承诺（与平台 V100:122-138 同一条规则）。
    rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota, '
                'audio_seconds_reserved, audio_seconds_used) '
                'VALUES (''VIDEO_SECOND'', 0, 0, 0)', probe_table);
    EXCEPTION WHEN check_violation THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'a_zero_audio_seconds_quota_was_allowed:definition=%', definition;
    END IF;

    EXECUTE format('DROP TABLE pg_temp.%I', probe_table);

    -- 4.4 预留单位列
    --
    -- 只读两列：INTO 的目标名若与 information_schema 的列名同名，PostgreSQL 会
    -- 把它判成**有歧义的列引用**（本迁移第一次运行就是这样失败的），
    -- 而这一处并不需要默认值。
    SELECT is_nullable, data_type
    INTO column_state, column_type
    FROM information_schema.columns
    WHERE table_schema = 'gateway' AND table_name = 'quota_reservation'
      AND column_name = 'usage_dimension';
    IF column_type IS NULL THEN
        RAISE EXCEPTION 'quota_reservation_usage_dimension_is_missing';
    END IF;
    IF column_state <> 'NO' OR column_type <> 'character varying' THEN
        RAISE EXCEPTION
            'quota_reservation_usage_dimension_must_be_a_not_null_varchar:nullable=%:type=%',
            column_state, column_type;
    END IF;

    SELECT pg_get_constraintdef(constraint_row.oid) INTO definition
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = 'gateway'
      AND table_row.relname = 'quota_reservation'
      AND constraint_row.conname = 'ck_reservation_usage_dimension';
    IF definition IS NULL OR position('AUDIO_SECOND' IN definition) = 0 THEN
        RAISE EXCEPTION
            'ck_reservation_usage_dimension_does_not_admit_AUDIO_SECOND:definition=%',
            definition;
    END IF;

    -- 回填必须没有遗漏，而且"预留声称的单位"必须是它所属实例真的能计费的单位：
    -- 主维度，或实例确实拥有一份额度时的 AUDIO_SECOND。新装库上这里是 0 行，
    -- 但它证明的是那条 UPDATE 与那条不变量的**正确性**，而不是"此刻没有数据"
    -- 这个偶发状态（V96:563-568 的同一条理由）。
    SELECT count(*) INTO mismatched
    FROM gateway.quota_reservation reservation
    JOIN gateway.package_instance package ON package.id = reservation.package_instance_id
    WHERE reservation.usage_dimension <> package.usage_dimension
      AND NOT (reservation.usage_dimension = 'AUDIO_SECOND'
               AND package.audio_seconds_quota IS NOT NULL);
    IF mismatched <> 0 THEN
        RAISE EXCEPTION
            'a_reservation_claims_a_unit_its_instance_cannot_bill:rows=%', mismatched;
    END IF;

    -- 4.5 触发器：定义里必须出现 AUDIO_SECOND（证明"按秒"那一侧真的被扩宽，
    --     而不是只改了注释），随后用真实 DML 证明两个方向。
    SELECT pg_get_functiondef(function_row.oid) INTO definition
    FROM pg_proc function_row
    JOIN pg_namespace schema_row ON schema_row.oid = function_row.pronamespace
    WHERE schema_row.nspname = 'gateway'
      AND function_row.proname = 'assert_package_entitlement_dimension';
    IF definition IS NULL THEN
        RAISE EXCEPTION 'assert_package_entitlement_dimension_is_missing';
    END IF;
    IF position('AUDIO_SECOND' IN definition) = 0
            OR position('VIDEO_SECOND' IN definition) = 0 THEN
        RAISE EXCEPTION
            'the_entitlement_guard_does_not_recognise_both_per_second_dimensions:definition=%',
            definition;
    END IF;

    -- 探针行必须满足 package_instance 上所有 NOT NULL 列与另外两条 CHECK
    -- （日期 CHECK 要求 QUEUED 行的 starts_at / expires_at 为空，因此不写它们）。
    --
    -- 混合权益（视频别名 + 音频别名）**必须被接受**：这是方案 C 的形态。
    -- 只捕获 raise_exception（P0001，即触发器自己的 RAISE）：别的失败类型
    -- （NOT NULL、别的 CHECK）照常向上抛，因为把一次与权益无关的插入失败
    -- 读成"触发器放行了"会让这条断言变成空话。
    rejected := false;
    BEGIN
        INSERT INTO gateway.package_instance(
            id, grant_event_id, grant_request_id, tenant_id, user_id,
            package_plan_version_id, package_code, package_name, package_kind,
            price_amount, allowed_models, total_token_quota, max_api_keys,
            requests_per_minute, tokens_per_minute, max_concurrency, duration_days,
            max_file_size_bytes, identity_key, once_per_user,
            usage_dimension, status, audio_seconds_quota)
        VALUES (
            probe_id, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
            gen_random_uuid(), 'V101_PROBE', 'V101 probe', 'STANDARD',
            100, '[{"modelCode":"video-basic"},{"modelCode":"audio-asr-basic"}]'::jsonb,
            600, 1, 1, 1, 1, 1,
            1024, 'v97-probe', false,
            'VIDEO_SECOND', 'QUEUED', 600);
    EXCEPTION
        WHEN raise_exception THEN rejected := true;
    END;
    IF rejected THEN
        RAISE EXCEPTION
            'a_video_alias_was_refused_a_package_that_also_sells_audio_seconds:definition=%',
            definition;
    END IF;
    DELETE FROM gateway.package_instance WHERE id = probe_id;

    -- 视频别名 + token 别名**必须仍然被拒绝**：V40 的原意，扩宽"按秒"那一侧
    -- 最容易顺手把它一起放走。
    rejected := false;
    BEGIN
        INSERT INTO gateway.package_instance(
            id, grant_event_id, grant_request_id, tenant_id, user_id,
            package_plan_version_id, package_code, package_name, package_kind,
            price_amount, allowed_models, total_token_quota, max_api_keys,
            requests_per_minute, tokens_per_minute, max_concurrency, duration_days,
            max_file_size_bytes, identity_key, once_per_user,
            usage_dimension, status)
        VALUES (
            gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),
            gen_random_uuid(), gen_random_uuid(),
            gen_random_uuid(), 'V101_PROBE2', 'V101 probe 2', 'STANDARD',
            100, '[{"modelCode":"video-basic"},{"modelCode":"text-basic"}]'::jsonb,
            600, 1, 1, 1, 1, 1,
            1024, 'v97-probe-2', false,
            'VIDEO_SECOND', 'QUEUED');
    EXCEPTION
        WHEN raise_exception THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION
            'a_video_alias_was_allowed_into_a_token_entitlement:definition=%', definition;
    END IF;
    DELETE FROM gateway.package_instance WHERE package_code IN ('V101_PROBE', 'V101_PROBE2');
END $$;
