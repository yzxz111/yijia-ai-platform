-- 方案 C 第 4 步的收口：第二份额度只属于"视频秒 + 音频秒"的版本。
--
-- V100 加这一列时，CHECK 允许它在 usage_dimension IN ('VIDEO_SECOND','AUDIO_SECOND')
-- 时非空。那份写法是当时能做的最窄判断——那时还没有任何代码读这一列，
-- 而"两种按秒维度都可能需要它"看起来是安全的宽容。
--
-- 第 4 步之后它不再安全：PackageSnapshotService 把它下发成
-- gateway.package_instance.audio_seconds_quota，而那个实例的第二份额度
-- **只在主维度是 VIDEO_SECOND 时**才会被结算读取（网关 V97 的
-- ck_package_instance_audio_seconds 就是这么写的）。于是一个纯音频版本
-- （usage_dimension = AUDIO_SECOND）若带上这一列，会出现：
--   * 平台发布成功、订单售出、发放时被网关的 CHECK 拒绝 ——
--     客户已经付过款，而失败出现在最后一步；
--   * 或者（如果网关的约束被放宽）它成为一个两份额度都说得通、
--     而结算只会读其中一份的额度：另一份是无声的谎。
--
-- 因此这里把规则收紧到与网关**逐字相同**的那一条：
--   audio_seconds_quota 只能出现在 usage_dimension = 'VIDEO_SECOND' 的版本上。
--
-- 为什么纯音频版本不需要它：那种版本的 total_token_quota 本身就是音频秒
-- ——主额度列的含义由 usage_dimension 决定（V42:16-22 给网关侧写过同样的理由），
-- 再填一份会让"这次请求扣哪一列"出现两个都说得通的答案。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
-- 参照：平台 V100（本列的引入）、网关 V97（同一条规则的网关侧）

-- ---------------------------------------------------------------------------
-- 1. 收紧约束
-- ---------------------------------------------------------------------------

ALTER TABLE platform.package_plan_version
    DROP CONSTRAINT ck_package_version_audio_seconds;

ALTER TABLE platform.package_plan_version
    ADD CONSTRAINT ck_package_version_audio_seconds
        CHECK (audio_seconds_quota IS NULL
               OR (audio_seconds_quota > 0
                   AND usage_dimension = 'VIDEO_SECOND'));

COMMENT ON COLUMN platform.package_plan_version.audio_seconds_quota IS
    'Optional SECOND quota for a VIDEO_SECOND version that also sells audio '
    'seconds (方案 C), mirrored into gateway.package_instance.audio_seconds_quota. '
    'NULL = this version sells no audio. Only a VIDEO_SECOND version may set it: a '
    'pure AUDIO_SECOND version already carries its audio seconds in total_token_quota, '
    'and the gateway would reject an instance that set both. Enforced identically on '
    'both sides (platform V101, gateway V97).';

-- ---------------------------------------------------------------------------
-- 2. 断言
-- ---------------------------------------------------------------------------
-- 收紧一个约束同样不会让任何东西失败——它只会让**将来**的非法写入被拒绝，
-- 而如果词写错了（例如把 = 写成 <>），被拒绝的会是合法写入，症状出现在
-- 一次发放上。因此这里既证明"合法的行被接受"，也证明"非法的行被拒绝"，
-- 而且都是让 PostgreSQL 拿真实约束去判断真实数据。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    definition       text;
    probe_table      text := 'probe_version_audio_seconds';
    probe_constraint text;
    probe_column     text;
    rejected         boolean;
    existing_rows    integer;
BEGIN
    -- 2.1 约束存在，且它现在只认 VIDEO_SECOND
    SELECT pg_get_constraintdef(constraint_row.oid) INTO definition
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
    WHERE schema_row.nspname = 'platform'
      AND table_row.relname = 'package_plan_version'
      AND constraint_row.conname = 'ck_package_version_audio_seconds';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_package_version_audio_seconds_is_missing';
    END IF;
    IF position('VIDEO_SECOND' IN definition) = 0 THEN
        RAISE EXCEPTION
            'the_constraint_must_admit_VIDEO_SECOND:definition=%', definition;
    END IF;
    -- 反向的**文本**证据：V100 那份写法里的 'AUDIO_SECOND' 必须已经不在里面。
    -- 只检查正向会放过"两个维度都还在"这种"收紧没生效"的结果。
    IF position('AUDIO_SECOND' IN definition) <> 0 THEN
        RAISE EXCEPTION
            'the_constraint_still_admits_a_pure_audio_version:definition=%', definition;
    END IF;

    -- 2.2 行为证明：克隆表、只留被测约束、去掉 NOT NULL，逐种取值插入见证行。
    EXECUTE format('DROP TABLE IF EXISTS pg_temp.%I', probe_table);
    EXECUTE format(
            'CREATE TEMP TABLE %I (LIKE platform.package_plan_version INCLUDING CONSTRAINTS) '
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

    -- 正向：视频秒版本携带一份音频秒额度（方案 C 的形态）。
    EXECUTE format(
            'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota) '
            'VALUES (''VIDEO_SECOND'', 600)', probe_table);
    -- 正向：绝大多数版本的形态——完全没有语音。
    EXECUTE format(
            'INSERT INTO pg_temp.%I (usage_dimension) VALUES (''TOKEN'')', probe_table);
    EXECUTE format(
            'INSERT INTO pg_temp.%I (usage_dimension) VALUES (''AUDIO_SECOND'')', probe_table);

    -- 反向 1：纯音频版本不得再带第二份额度（这就是本次收紧的那一条）。
    rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota) '
                'VALUES (''AUDIO_SECOND'', 600)', probe_table);
    EXCEPTION WHEN check_violation THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION
            'a_pure_audio_version_was_allowed_to_carry_the_second_quota:definition=%',
            definition;
    END IF;

    -- 反向 2：token / 图片版本不得携带它（V100 已有，收紧时最容易丢的一条）。
    FOR probe_column IN SELECT unnest(ARRAY['TOKEN', 'IMAGE'])
    LOOP
        rejected := false;
        BEGIN
            EXECUTE format(
                    'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota) '
                    'VALUES (%L, 600)', probe_table, probe_column);
        EXCEPTION WHEN check_violation THEN rejected := true;
        END;
        IF NOT rejected THEN
            RAISE EXCEPTION
                'a_%_version_was_allowed_to_carry_audio_seconds:definition=%',
                probe_column, definition;
        END IF;
    END LOOP;

    -- 反向 3：0 秒额度仍是"包含语音"的假承诺（V100 的规则不得被顺手丢掉）。
    rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, audio_seconds_quota) '
                'VALUES (''VIDEO_SECOND'', 0)', probe_table);
    EXCEPTION WHEN check_violation THEN rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'a_zero_audio_seconds_quota_was_allowed:definition=%', definition;
    END IF;

    EXECUTE format('DROP TABLE pg_temp.%I', probe_table);

    -- 2.3 收紧不得改变任何既有行。DROP/ADD 本身会在这一句之前就因为一条
    -- 违反新规则的行而失败，因此这里的价值不是"再检查一次"，而是把那条
    -- 隐含规则写下来：一个已经卖出去的版本不可能因为这次收紧而变得非法。
    SELECT count(*) INTO existing_rows
    FROM platform.package_plan_version version
    WHERE version.audio_seconds_quota IS NOT NULL
      AND version.usage_dimension <> 'VIDEO_SECOND';
    IF existing_rows <> 0 THEN
        RAISE EXCEPTION
            'the_tightened_constraint_invalidates_an_existing_version:rows=%',
            existing_rows;
    END IF;
END $$;
