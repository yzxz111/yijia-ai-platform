-- 音频计量维度：AUDIO_SECOND
--
-- 需求把三个音频能力带进网关：提取口播并生成带时间戳的字幕
-- （qwen-audio-3.0-asr-flash）、把文案转成配音（qwen-audio-3.0-tts-flash）、
-- 用配音替换人物视频口型（videoretalk）。本次改动之前，全项目
-- `grep -i 'AUDIO|ASR|TTS|voice'` 在 gateway-service/src/main/java 下
-- 返回零结果：网关没有任何音频概念。
--
-- ---------------------------------------------------------------------------
-- 为什么是**一个** AUDIO_SECOND，而不是 AUDIO_CHARACTER 或两个维度
-- ---------------------------------------------------------------------------
--
-- 考虑过并把它们否掉的三个方案：
--
-- 1. AUDIO_CHARACTER（更贴近 TTS 的厂商计价口径——百炼按 1 元/万字符
--    计费）。否掉的理由是 V42:65-68 把 seconds_quota 这一列硬绑给了
--    "非 TOKEN 维度"：`reserved_tokens + used_tokens <= seconds_quota`。
--    往里塞一个按字符计费的维度，就会得到一个**名为 seconds_quota
--    却装着字符数**的列——这正是 V40 的 ck_model_usage_price_shape
--    存在的目的（它拒绝让 token 行携带每秒价格，也拒绝让秒行携带
--    每百万价格）。一个单位混乱的配额列比少一个维度危险得多。
--
--    TTS 按字符报价这件事，用**成本换算**解决而不引入新单位：
--    ¥1.00 / 10000 字符，按最坏情况 2 字符/秒（正常中文narration是
--    7-8 字符/秒，最坏情况取 4 倍密度）折成 0.02 分/秒。存峰值而不是
--    平均值，理由与 V81:312-318 给图片定的规矩完全相同：
--    存低值会让毛利守卫放行一个在高档位上亏钱的售价。
--
-- 2. ASR_SECOND 与 TTS_SECOND 两个维度。否掉的理由是它买不到任何东西：
--    一个维度已经允许按别名、按能力分别定价，而拆开要把两个 schema 里
--    七处 CHECK 的词表翻倍，却换不来一分额外的表达力。
--
-- 3. 把音频并进视频池。否掉的理由是数量级：video-basic 的成本是
--    69 分/秒，ASR 是 0.022 分/秒。1:1 混用意味着一次 ASR 请求
--    按视频秒计价，即约 3136 倍的错价。
--
-- AUDIO_SECOND 因此与 VIDEO_SECOND 完全同形：同一列 seconds_quota、
-- 同一套 reserve→settle→release 形状、同样 minimum_units = 1。
--
-- ---------------------------------------------------------------------------
-- 本次改动**不**建立任务表，以及为什么
-- ---------------------------------------------------------------------------
--
-- 三个音频能力的协议形状不同：ASR 与 TTS 是同步的（一次请求一次响应），
-- 而 videoretalk 是异步任务（提交 + 轮询，且百炼限制每账号仅 1 个并发作业）。
-- 后者的幂等锚点需要一个与 image_generation_task 同形的表，但它应当**在
-- 适配器接口定型之后**再建：表结构是由"要存什么"决定的，而那个答案
-- 现在还不存在。先建表等于先猜。
--
-- 本迁移只做词表与两个纯新增对象，它们不依赖任何未定的接口。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md
-- 参照：gateway V40（费率卡词表）、V41/V42（秒维度）、
--      V49（用量经济维度）、V80（套餐维度）、V81（图片维度怎么加的）

-- ---------------------------------------------------------------------------
-- 1. 费率卡形状：AUDIO_SECOND 走与 VIDEO_SECOND/IMAGE 完全相同的分支
--
-- 本表上有**两条**约束枚举了维度：形状（本段）与取值列表（本段末尾）。
-- 两条都必须更新——只改形状那一条，音频费率行要到 V97 才以
-- "violates check constraint ck_model_usage_price_dimension" 失败，
-- 与真正的原因隔了一次 INSERT。
--
-- 形状这一条只 drop 一个 CHECK，而且只改这一个值列表。V40:68-80 把这条规则写成
-- 两半：TOKEN 那一半要求每百万价格、禁止单位价格；另一半
-- `usage_dimension IN ('VIDEO_SECOND','IMAGE')` 要求单位价格、禁止
-- 每百万价格。第二半的语义对 AUDIO_SECOND **完全适用**：一个
-- AUDIO_SECOND 售卖行只携带一个单位价格（分/秒），绝不能携带
-- 每百万 token 的价格。把它加进那个 IN 列表，而不是新写一个分支，
-- 正是为了让"秒维度只有一个单位价格"这条规则保持单一来源。
-- 两半都原样照抄 V40，只多一个词。
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.model_usage_price DROP CONSTRAINT ck_model_usage_price_shape;

ALTER TABLE gateway.model_usage_price ADD CONSTRAINT ck_model_usage_price_shape CHECK (
    (usage_dimension = 'TOKEN'
        AND resolution_tier = ''
        AND input_price_per_million > 0
        AND output_price_per_million > 0
        AND unit_price IS NULL)
    OR (usage_dimension IN ('VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND')
        AND unit_price > 0
        AND input_price_per_million IS NULL
        AND output_price_per_million IS NULL
        AND cache_hit_price_per_million IS NULL
        AND cache_write_price_per_million IS NULL
        AND reasoning_price_per_million IS NULL));

-- 第二条：V40:60-61 的纯取值白名单。它只列允许的维度、不谈形状，
-- 因此改形状的时候很容易被漏掉——而漏掉它的症状出现在**另一个文件**里
-- （V97 插入音频费率行时报 ck_model_usage_price_dimension），
-- 距离真正的原因隔了一次 INSERT。一个维度必须在每一条枚举它的约束里出现。
ALTER TABLE gateway.model_usage_price DROP CONSTRAINT ck_model_usage_price_dimension;

ALTER TABLE gateway.model_usage_price ADD CONSTRAINT ck_model_usage_price_dimension
    CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- 第三条：档位词表。V81:50-56 把它写成**逐维度的析取**，而不是一个 IN 列表，
-- 因此 AUDIO_SECOND 不在任何分支里——结果是**任何**音频行都会被它拒绝。
--
-- 这条最隐蔽，原因有两个：它不枚举"维度"，所以按「哪些约束列了维度」去查会
-- 漏掉它；而且下面那条反向断言「音频行不得声称 720P」在它未被修好时**照样通过**
-- ——约束拒绝了全部音频行，"拒绝 720P"自然也为真。一个被过度收紧的约束会让
-- 负向断言侥幸通过，所以下面同时补一条正向断言（'' 必须被接受）。
--
-- AUDIO_SECOND 与 TOKEN 同形：单位就是秒，没有尺寸阶梯，故 resolution_tier = ''。
ALTER TABLE gateway.model_usage_price DROP CONSTRAINT ck_model_usage_price_tier;

ALTER TABLE gateway.model_usage_price ADD CONSTRAINT ck_model_usage_price_tier
    CHECK (
        (usage_dimension = 'TOKEN' AND resolution_tier = '')
        OR (usage_dimension = 'VIDEO_SECOND'
            AND resolution_tier IN ('480P', '720P', '1080P'))
        OR (usage_dimension = 'IMAGE'
            AND resolution_tier IN ('1K', '2K', '4K'))
        OR (usage_dimension = 'AUDIO_SECOND' AND resolution_tier = ''));

COMMENT ON COLUMN gateway.model_usage_price.resolution_tier IS
    'Size tier the row prices. TOKEN: always ''''. VIDEO_SECOND: 480P / 720P / '
    '1080P. IMAGE: 1K / 2K / 4K (see V81). AUDIO_SECOND: always '''' (see V96) -- '
    'the unit is a second and there is no size ladder. The tier is part of the '
    'rate card identity, so one alias may hold one row per tier and dimension.';

-- ---------------------------------------------------------------------------
-- 2. 其余五处维度词表
--
-- 每一次都是 drop 一个只讲维度的 CHECK 再原样加回、只多一个值。
-- **刻意不碰**任何形状/取值 CHECK：它们已经用 `usage_dimension <> 'TOKEN'`
-- 或 `= 'TOKEN'` 的形式表达，因此 AUDIO_SECOND 自动落进"秒类维度"那一侧，
-- 无需再改。这正是当初把它们写成不等式而不是值列表的价值。
-- ---------------------------------------------------------------------------

-- V42:26 的 ck_package_instance_usage_dimension。
ALTER TABLE gateway.package_instance DROP CONSTRAINT ck_package_instance_usage_dimension;
ALTER TABLE gateway.package_instance ADD CONSTRAINT ck_package_instance_usage_dimension
    CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- V40:344 的 ck_model_quota_dimension。
ALTER TABLE gateway.package_instance_model_quota DROP CONSTRAINT ck_model_quota_dimension;
ALTER TABLE gateway.package_instance_model_quota ADD CONSTRAINT ck_model_quota_dimension
    CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- V49:42 的 ck_usage_log_usage_dimension。
ALTER TABLE gateway.usage_log DROP CONSTRAINT ck_usage_log_usage_dimension;
ALTER TABLE gateway.usage_log ADD CONSTRAINT ck_usage_log_usage_dimension
    CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- V49:95 的 ck_usage_stage_usage_dimension。
ALTER TABLE gateway.usage_stage DROP CONSTRAINT ck_usage_stage_usage_dimension;
ALTER TABLE gateway.usage_stage ADD CONSTRAINT ck_usage_stage_usage_dimension
    CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- V80:25 的 ck_api_key_usage_dimension。它在 gateway.api_key 上，不在
-- package_instance 上：V80 给 key 也加了维度，因为激活时的重绑必须只作用于
-- 同维度的 key（否则激活一个音频套餐会把该用户所有文本 key 抢过去）。
-- 漏掉这一处会让一把音频 key 在绑定时违反约束。
ALTER TABLE gateway.api_key DROP CONSTRAINT ck_api_key_usage_dimension;
ALTER TABLE gateway.api_key ADD CONSTRAINT ck_api_key_usage_dimension
    CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE', 'AUDIO_SECOND'));

-- V83:53 的 ck_usage_stage_type：音频生成要有一行合法的 stage 类型。
--
-- **注意这个值列表必须包含 IMAGE_GENERATION。** V83 已经把这条约束
-- 改写成四个值（V13 的两个对话阶段、V49 的视频、V83 的图片），
-- 因此在这里只写 V49 那三个加音频，会**悄悄地把 IMAGE_GENERATION 删掉**——
-- 一条"加了东西"的迁移顺手删掉另一个维度的能力，而这不会让任何东西失败，
-- 只会让图片结算在下一次写入时炸掉。值列表因此完整重述，且下面的断言
-- 逐个检查五个值，缺任何一个都会失败。
--
-- 与 IMAGE_GENERATION 并列而不是复用 FINAL_RESPONSE：后者的语义是
-- "一次对话的最终应答"，把一次 TTS 或一次转写记成它是错的账。
ALTER TABLE gateway.usage_stage DROP CONSTRAINT ck_usage_stage_type;
ALTER TABLE gateway.usage_stage ADD CONSTRAINT ck_usage_stage_type CHECK (
    stage_type IN ('FINAL_RESPONSE', 'FILE_ANALYSIS', 'VIDEO_GENERATION',
                   'IMAGE_GENERATION', 'AUDIO_GENERATION'));

COMMENT ON COLUMN gateway.usage_stage.stage_type IS
    'Which production step this stage describes. FINAL_RESPONSE / FILE_ANALYSIS are chat '
    'stages (V13), VIDEO_GENERATION is a per-second video generation (V49), '
    'IMAGE_GENERATION is a per-image generation settled in IMAGE units (V83), '
    'AUDIO_GENERATION is a per-second audio generation settled in AUDIO_SECOND units (V96). '
    'The stage type names the step, never the unit: the unit is usage_dimension.';

-- stage_type 与 usage_dimension 的列注释已由 V49 与 V83 写为权威版本，
-- 本迁移不再重复 COMMENT：两处注释会互相覆盖，而 V49/V83 的那两句
-- 描述的是整列的含义，不是本次新增的那个值。

-- ---------------------------------------------------------------------------
-- 3. 音频别名的档位登记表
--
-- 与 V81 的 image_alias_tier、V54 的 video_alias_tier 同形，理由也一样：
-- 一个别名必须有一个已登记的档位，服务层才能在定价之前回答
-- "这个别名是按什么卖的"。对音频而言这个答案永远是"秒"，
-- 而把它写下来（而不是让代码默认它）保住了那条不变量：
-- **一个没有登记档位的售卖别名必须被拒绝，而不是被猜**。
--
-- 与图片/视频相比少一层阶梯：那两个维度要按 1K/2K/4K 或 480P/720P/1080P
-- 再分档，而音频只有一种计量方式，因此这里只登记单位而不登记档位。
-- 单位本身仍然带一条 CHECK，哪怕当前只有一个合法值：它把一个未来新增
-- 第二计量单位（例如按字符）变成一次**需要写迁移的决定**，而不是代码里
-- 一个悄悄生效的默认值。这正是 V83 给 unit_label 加 CHECK 的同一个理由。
-- ---------------------------------------------------------------------------

CREATE TABLE gateway.audio_alias_tier (
    model_code varchar(128) PRIMARY KEY
        REFERENCES gateway.model(code) ON DELETE CASCADE,
    billing_unit varchar(16) NOT NULL DEFAULT 'SECOND',
    registered_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_audio_alias_tier_unit CHECK (billing_unit IN ('SECOND'))
);

COMMENT ON TABLE gateway.audio_alias_tier IS
    'The billing unit each audio generation alias sells. Mirrors V81''s '
    'image_alias_tier and V54''s video_alias_tier. Enforced by AudioGenerationService: '
    'an alias with no row here is refused with audio_model_unit_unregistered rather '
    'than assumed to be per-second.';
COMMENT ON COLUMN gateway.audio_alias_tier.billing_unit IS
    'Always SECOND. Audio has exactly one honest unit (see the header: a character '
    'dimension would put characters into seconds_quota). The column exists so a '
    'future second unit has to be a deliberate migration, not a code default.';

-- ---------------------------------------------------------------------------
-- 4. 音频的供应商成本视图
--
-- V41/V45 的 video_effective_cost 被硬过滤在 VIDEO_SECOND 上，而它
-- effective_unit_cost 的单位是**分/秒**；V81 的 image_effective_cost
-- 是分/张。一个消费者若从视频视图里读到一张图片行，会把像素当成秒来缩放。
-- 音频同理，因此新开一个平行的视图，单位是分/秒。
--
-- **retry 允许多算一次。** 这里采用 V81 图片视图的算法
-- （ceil(provider_unit_cost * (1 + retry_rate))），而不是 V45 视频视图的
-- "存进去的成本已经含 retry、直接透传"。两个视频视图之间那个不一致是
-- V45 记录的真实事故（V41 重复计了一次 retry），原因是 V40 把
-- provider_unit_cost 存成了 retry-inclusive 的 69 分。
--
-- 音频这边明确选择 V81 的形状：provider_unit_cost 存**厂商原价**
-- （ASR 0.022 分/秒、TTS 0.02、videoretalk 8），retry_rate 单独记录，
-- 由视图合成有效成本。这样"原价"与"失败允差"是两个可以分别核对的数，
-- 而不是一个混合值。费率行写入者必须遵守这条约定：
-- **存原价，不要预先乘上 retry_rate，否则会被算两次。**
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW gateway.audio_effective_cost AS
SELECT price.model_code,
       price.resolution_tier,
       price.provider_unit_cost,
       price.retry_rate,
       price.min_absolute_margin,
       price.target_margin_ratio,
       price.unit_price,
       -- minimum_charge 与 minimum_units 一起暴露，因为「按秒计价」的维度
       -- 有一个别处不存在的性质：一次很短的请求，其成本几乎为零，
       -- 而**每次请求的固定开销**（认证、路由、落库、OSS、对账）是恒定的。
       -- 因此这些维度的真实单价由 minimum_charge 而不是 unit_price 决定，
       -- 而一个读这个视图的消费者若看不到它，就会把 rate card 误读成
       -- 「一秒 5 分」而不是「一次调用至少 5 分」。视频与图片视图有同样的
       -- 形状（V45/V81 都暴露 unit_price 与 min_absolute_margin），
       -- 这里与它们保持一致。
       price.minimum_units,
       price.minimum_charge,
       price.price_version,
       ceil(price.provider_unit_cost * (1 + price.retry_rate))::bigint AS effective_unit_cost
FROM gateway.model_usage_price price
WHERE price.usage_dimension = 'AUDIO_SECOND'
  AND price.effective_to IS NULL;

COMMENT ON VIEW gateway.audio_effective_cost IS
    'Current per-second effective provider cost for audio aliases, in 分 per '
    'SECOND. Parallel to gateway.video_effective_cost (分/秒, hard-filtered to '
    'VIDEO_SECOND) and gateway.image_effective_cost (分/张). Read by '
    'ProviderPriceQueryRepository for /internal/prices/effective-unit-cost.';
COMMENT ON COLUMN gateway.audio_effective_cost.provider_unit_cost IS
    'Vendor list cost per second, WITHOUT the retry allowance. The view applies '
    'retry_rate itself, so a rate card row that pre-multiplies it charges the '
    'failure allowance twice -- the V41/V45 defect, recorded here so it is not '
    'repeated in a third dimension.';

-- ---------------------------------------------------------------------------
-- 5. 断言
--
-- 这是本次改动里最高风险的一条迁移：它放宽了七个 CHECK 约束，
-- 而放宽一个约束不会让任何东西失败——它只会让**将来**的非法写入被接受。
-- 因此下面不满足于"迁移跑完了"，而是逐个证明每个被改写的约束
-- **确实接受** AUDIO_SECOND。做法是把约束自己的表达式取出来、
-- 把列引用换成字面量后求值，断言它必须为真——而不是复述我刚写下的字面量。
--
-- 参照 V83:87-91 的同一思路：一个直接写入受约束表的迁移必须自己
-- 证明写入合法，否则约束被放宽或被超级用户绕过时没有任何东西会失败。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    dimension_checks text[] := ARRAY[
        'ck_model_usage_price_shape',
        'ck_model_usage_price_dimension',
        'ck_model_usage_price_tier',
        'ck_package_instance_usage_dimension',
        'ck_model_quota_dimension',
        'ck_usage_log_usage_dimension',
        'ck_usage_stage_usage_dimension',
        'ck_api_key_usage_dimension'
    ];
    -- 每个约束所属的表，用于取出它的定义。与上一数组**按下标一一对应**：
    -- 两个数组长度不一致或顺序错位，都会让断言去检查错误的对象。
    check_tables text[] := ARRAY[
        'model_usage_price',
        'model_usage_price',
        'model_usage_price',
        'package_instance',
        'package_instance_model_quota',
        'usage_log',
        'usage_stage',
        'api_key'
    ];
    index integer;
    definition text;
    probe_table text;
    probe_column text;
    probe_constraint text;
    tier_rejected boolean;
    broken integer;
BEGIN
    -- 逐个证明：被改写的约束的当前定义里必须出现 AUDIO_SECOND。
    -- 检查的是 pg_get_constraintdef 的**实际文本**，而不是我写下的 SQL——
    -- 若一次 DROP 成功而 ADD 被跳过（例如名字写错），
    -- 这里的 definition 会是 NULL，下面立刻失败。
    FOR index IN 1 .. array_length(dimension_checks, 1) LOOP
        SELECT pg_get_constraintdef(constraint_row.oid)
        INTO definition
        FROM pg_constraint constraint_row
        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
        JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
        WHERE schema_row.nspname = 'gateway'
          AND table_row.relname = check_tables[index]
          AND constraint_row.conname = dimension_checks[index];

        IF definition IS NULL THEN
            RAISE EXCEPTION 'dimension_constraint_missing:table=%:constraint=%',
                check_tables[index], dimension_checks[index];
        END IF;

        IF position('AUDIO_SECOND' IN definition) = 0 THEN
            RAISE EXCEPTION
                'dimension_constraint_does_not_admit_AUDIO_SECOND:table=%:constraint=%:definition=%',
                check_tables[index], dimension_checks[index], definition;
        END IF;
    END LOOP;

    -- 上面只证明了约束的**文本**里出现了 AUDIO_SECOND。文本可以在提到它的
    -- 同时仍然拒绝它——例如被 AND 进一个更窄的条件里。
    --
    -- 下面做**行为**证明：克隆目标表（`LIKE ... INCLUDING CONSTRAINTS` 会把
    -- CHECK 一起复制过来，且不带外键与唯一索引），在克隆体上只留下被测的
    -- 那一条 CHECK、去掉所有 NOT NULL，再插入一个**见证行**——一个除维度
    -- 之外其余列取最小合法值的行。约束若真的接受 AUDIO_SECOND，这次 INSERT
    -- 就会成功；若它把 AUDIO_SECOND 排除在外、或把它塞进了另一条分支，
    -- 这里会以 check_violation 失败。
    --
    -- **为什么不把表达式取出来求值**（本文件初版的做法）：CHECK 表达式会引用
    -- 该表的**所有**列，只替换 usage_dimension 之后求值必然以
    -- 'column "resolution_tier" does not exist' 之类的错误失败——那是个与
    -- 要证明的事情完全无关的失败，而且它看起来像迁移本身写错了。
    -- 让 PostgreSQL 拿真实的约束去判断一行真实的数据，才是这条断言想要的。
    FOR index IN 1 .. array_length(dimension_checks, 1) LOOP
        SELECT pg_get_constraintdef(constraint_row.oid)
        INTO definition
        FROM pg_constraint constraint_row
        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
        JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
        WHERE schema_row.nspname = 'gateway'
          AND table_row.relname = check_tables[index]
          AND constraint_row.conname = dimension_checks[index];

        probe_table := 'probe_' || dimension_checks[index];
        EXECUTE format('DROP TABLE IF EXISTS pg_temp.%I', probe_table);
        EXECUTE format(
                'CREATE TEMP TABLE %I (LIKE gateway.%I INCLUDING CONSTRAINTS) ON COMMIT DROP',
                probe_table, check_tables[index]);

        -- 只留被测的那一条 CHECK。表上其它约束与"维度是否被接受"无关，
        -- 却可能拒绝这个专为证明维度而构造的见证行，从而把失败指向错误的地方。
        -- 按**定义文本**而不是约束名来配对：LIKE ... INCLUDING CONSTRAINTS
        -- 复制出来的 CHECK 不一定沿用原名，但定义文本是逐字相同的。
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

        -- 见证行只填它需要的列，因此先去掉克隆体上的 NOT NULL。
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

        BEGIN
            IF dimension_checks[index] = 'ck_model_usage_price_shape' THEN
                -- 秒维度分支要求单位价格为正，且各个 *_per_million 价格为空；
                -- 这里只给 usage_dimension 与 unit_price，其余列留空即为 NULL。
                EXECUTE format(
                        'INSERT INTO pg_temp.%I (usage_dimension, unit_price) '
                        'VALUES (''AUDIO_SECOND'', 1)', probe_table);
            ELSIF dimension_checks[index] = 'ck_model_usage_price_tier' THEN
                -- 档位约束按维度分支判断，只给 usage_dimension 不够：
                -- resolution_tier 留空是 NULL，而 CHECK 遇到 NULL 结果为 NULL、
                -- 于是**照样通过**——那样这条正向证明就是空的。
                -- 音频的合法档位是 ''（与 TOKEN 同形），因此显式写出它。
                EXECUTE format(
                        'INSERT INTO pg_temp.%I (usage_dimension, resolution_tier) '
                        'VALUES (''AUDIO_SECOND'', '''')', probe_table);
            ELSE
                -- 其余几条都只约束 usage_dimension 的取值。
                EXECUTE format(
                        'INSERT INTO pg_temp.%I (usage_dimension) VALUES (''AUDIO_SECOND'')',
                        probe_table);
            END IF;
        EXCEPTION
            WHEN check_violation THEN
                RAISE EXCEPTION
                    'dimension_constraint_rejects_AUDIO_SECOND:table=%:constraint=%:definition=%',
                    check_tables[index], dimension_checks[index], definition;
        END;

        EXECUTE format('DROP TABLE pg_temp.%I', probe_table);
    END LOOP;

    -- 阶段类型词表：五个值一个都不能少。
    --
    -- 这条断言存在的理由是一次真实的近失：本迁移重述 ck_usage_stage_type
    -- 时，若只照 V49 的三个值加上音频，就会**悄悄删掉 V83 的
    -- IMAGE_GENERATION**——加东西的迁移顺手拿走另一个维度的能力，
    -- 而且不会有任何东西失败，直到图片结算下一次写入。因此逐个检查，
    -- 包括本次并没有新增的那两个。
    SELECT pg_get_constraintdef(constraint_row.oid)
    INTO definition
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    WHERE table_row.relname = 'usage_stage'
      AND constraint_row.conname = 'ck_usage_stage_type';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_usage_stage_type_missing';
    END IF;

    IF position('AUDIO_GENERATION' IN definition) = 0 THEN
        RAISE EXCEPTION
            'ck_usage_stage_type_does_not_admit_AUDIO_GENERATION:definition=%', definition;
    END IF;

    IF position('IMAGE_GENERATION' IN definition) = 0
            OR position('VIDEO_GENERATION' IN definition) = 0
            OR position('FINAL_RESPONSE' IN definition) = 0
            OR position('FILE_ANALYSIS' IN definition) = 0 THEN
        RAISE EXCEPTION
            'ck_usage_stage_type_lost_an_existing_stage_type:definition=%', definition;
    END IF;

    -- 反向行为检查与正向同样重要：放宽维度词表**不得**顺手放宽档位词表。
    -- 做法与上面第一个循环对称：克隆 model_usage_price、只留被测的
    -- ck_model_usage_price_tier，然后插入一行 usage_dimension = 'AUDIO_SECOND'
    -- 且 resolution_tier = '720P'。这一行**必须被拒绝**——音频维度没有分辨率
    -- 阶梯（V83 为图片档位写过同样的理由）。
    SELECT pg_get_constraintdef(constraint_row.oid)
    INTO definition
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    WHERE table_row.relname = 'model_usage_price'
      AND constraint_row.conname = 'ck_model_usage_price_tier';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_model_usage_price_tier_missing';
    END IF;

    probe_table := 'probe_ck_model_usage_price_tier';
    EXECUTE format('DROP TABLE IF EXISTS pg_temp.%I', probe_table);
    EXECUTE format(
            'CREATE TEMP TABLE %I (LIKE gateway.model_usage_price INCLUDING CONSTRAINTS) ON COMMIT DROP',
            probe_table);
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

    tier_rejected := false;
    BEGIN
        EXECUTE format(
                'INSERT INTO pg_temp.%I (usage_dimension, resolution_tier) '
                'VALUES (''AUDIO_SECOND'', ''720P'')', probe_table);
    EXCEPTION
        WHEN check_violation THEN
            -- 期望路径：档位约束拒绝了这一行。
            tier_rejected := true;
    END;

    IF NOT tier_rejected THEN
        RAISE EXCEPTION
            'an_audio_row_may_claim_a_video_resolution_tier:definition=%', definition;
    END IF;

    EXECUTE format('DROP TABLE pg_temp.%I', probe_table);

    -- audio_alias_tier 必须存在，且它的取值词表只认 SECOND。
    IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'gateway' AND table_name = 'audio_alias_tier') THEN
        RAISE EXCEPTION 'audio_alias_tier_missing';
    END IF;
    -- 词表断言同样走行为：这个约束必须拒绝一个未知单位。
    SELECT pg_get_constraintdef(constraint_row.oid)
    INTO definition
    FROM pg_constraint constraint_row
    JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
    WHERE table_row.relname = 'audio_alias_tier'
      AND constraint_row.conname = 'ck_audio_alias_tier_unit';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_audio_alias_tier_unit_missing';
    END IF;

    BEGIN
        EXECUTE 'INSERT INTO gateway.audio_alias_tier (model_code, billing_unit) '
                || 'SELECT ''text-basic'', ''MINUTE'' '
                || 'WHERE EXISTS (SELECT 1 FROM gateway.model WHERE code = ''text-basic'')';
        -- 只有在真的插进去时才失败：text-basic 是基座目录里的别名，
        -- 一定存在，因此 WHERE EXISTS 不会是"没测到"的借口。
        RAISE EXCEPTION
            'audio_alias_tier_accepted_an_unknown_billing_unit:definition=%', definition;
    EXCEPTION
        WHEN check_violation THEN
            NULL; -- 期望路径。
        WHEN raise_exception THEN
            RAISE;
    END;

    -- audio_effective_cost 必须存在，并且**真的被 AUDIO_SECOND 过滤**。
    --
    -- 这里刻意**不**断言它为空。本迁移没有播下任何音频费率行，因此「空」
    -- 此刻确实是它的状态——但把一个「此刻为空」当作不变量钉住，会让紧随其后的
    -- V97（它正是来播下那三行的）在应用时撞上它。迁移断言应当陈述 schema 的
    -- 不变量，而不是陈述某一条迁移运行时的偶发状态。
    --
    -- 因此这里证明的是那条过滤条件本身：往 model_usage_price 里放一行
    -- **VIDEO_SECOND**，视图必须看不见它。这与 V97 里「三个维度各自的视图
    -- 不得为同一个别名同时给出成本」的断言互补——后者管跨维度冲突，
    -- 这里管单维度的过滤是否真的生效。
    IF NOT EXISTS (
            SELECT 1 FROM information_schema.views
            WHERE table_schema = 'gateway' AND table_name = 'audio_effective_cost') THEN
        RAISE EXCEPTION 'audio_effective_cost_view_missing';
    END IF;

    -- 视图的定义文本里必须同时出现 AUDIO_SECOND 与 model_usage_price：
    -- 前者证明它按维度过滤，后者证明它读的是费率卡而不是别的表。
    SELECT pg_get_viewdef('gateway.audio_effective_cost'::regclass, true)
    INTO definition;

    IF definition IS NULL
            OR position('AUDIO_SECOND' IN definition) = 0
            OR position('model_usage_price' IN definition) = 0 THEN
        RAISE EXCEPTION
            'audio_effective_cost_view_does_not_filter_on_the_audio_dimension:definition=%',
            definition;
    END IF;

    -- 行为检查：视图对**音视频三套**维度必须只有一套结果。
    -- 一个别名在两个视图里同时出现会让 /internal/prices/effective-unit-cost
    -- 整体丢弃它（HAVING count(*) = 1），于是该别名静默地无法定价。
    -- V97 在播下音频费率行后重述同一条规则；这里先证明视图本身不会
    -- 把别的维度的行漏进来。
    SELECT count(*) INTO broken
    FROM (
        SELECT model_code FROM gateway.video_effective_cost
        INTERSECT
        SELECT model_code FROM gateway.audio_effective_cost
    ) leaked;

    IF broken > 0 THEN
        RAISE EXCEPTION
            'an_alias_appears_in_both_the_video_and_audio_cost_views:count=%', broken;
    END IF;
END $$;
