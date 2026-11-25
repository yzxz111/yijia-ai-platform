-- 音频生成的幂等锚点。
--
-- V96 建立了 AUDIO_SECOND 维度，V97 建立了三个音频别名与费率卡。本迁移补上
-- 两者之间缺的那一环：**一次已付费的音频调用必须有一个地方记录它已经发生过**，
-- 否则一次客户端重试就是第二次付费调用。
--
-- ---------------------------------------------------------------------------
-- 为什么一张表承载三个能力，而不是三张表
-- ---------------------------------------------------------------------------
--
-- 三个音频能力的协议形状不同：
--
--   audio-asr-basic   同步   一次请求 → 文本 + 时间戳
--   audio-tts-basic   同步   一次请求 → 一个音频位置
--   audio-retalk      异步   提交 → 轮询 → 一个视频位置
--
-- 但它们**需要记录的字段几乎完全相同**：预留了多少秒、结算了多少秒、
-- 用了哪条渠道、上游是哪个模型、终态是什么、失败分类是什么。
-- 差别只有两处：异步那个多一个上游 task id，以及产物是什么形态。
--
-- 因此这里像 V81 的 image_generation_task 那样，用一张表加一个 kind 判别列，
-- 而不是三张形状重复的表。**image_generation_task 正是先例**：图片生成也是
-- 同步的，它同样不是任务队列，而是幂等锚点。同步不等于「不需要记录」——
-- 一次同步调用在超时之后仍然可能已经在上游被计费，而客户端会重试。
--
-- 三张表的代价不是三倍的存储，而是三份会各自漂移的约束与三处恢复扫描。
--
-- ---------------------------------------------------------------------------
-- 与 video_generation_task / image_generation_task 的关系
-- ---------------------------------------------------------------------------
--
-- 不合并进任何一张。理由是那两个表的 CHECK 把维度钉死在自己的值上
-- （ck_video_task_dimension = 'VIDEO_SECOND'、ck_image_task_dimension = 'IMAGE'），
-- 而放宽它们会让「一张视频任务表里出现一行音频」变成合法状态——
-- 那正是那些约束存在的目的。本表同样把自己的维度钉死为 AUDIO_SECOND。
--
-- 设计：docs/superpowers/specs/2026-09-30-audio-and-tianyi-model-onboarding-design.md

CREATE TABLE gateway.audio_generation_task (
    id uuid PRIMARY KEY,
    request_id varchar(128) NOT NULL UNIQUE,
    reservation_id uuid NOT NULL REFERENCES gateway.quota_reservation(id) ON DELETE RESTRICT,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    api_key_id uuid,
    package_instance_id uuid,
    model_code varchar(128) NOT NULL,
    provider_id uuid NOT NULL REFERENCES gateway.provider(id) ON DELETE RESTRICT,
    channel_id uuid NOT NULL REFERENCES gateway.channel(id) ON DELETE RESTRICT,
    upstream_model varchar(128) NOT NULL,
    usage_dimension varchar(20) NOT NULL DEFAULT 'AUDIO_SECOND',

    -- 三个能力共用的判别列。它同时决定产物是文本还是位置，
    -- 以及终态是否可能带一个上游 task id。
    kind varchar(24) NOT NULL,

    requested_seconds integer NOT NULL,
    reserved_seconds integer NOT NULL,
    billed_seconds integer,

    -- 只有 audio-retalk 会填它：转写与合成没有上游任务可以轮询。
    -- 唯一键按 (provider_id, upstream_task_id) 建，与 video_generation_task 同形，
    -- 使一次重复提交无法在同一供应商下留下两个指向同一上游任务的锚点。
    upstream_task_id varchar(256),

    -- 同步能力（转写、合成）的产物：转写是文本加时间戳分段，合成是音频位置。
    -- 只有异步能力用它存不了的那部分才落到 output_url。
    result_payload jsonb,

    -- 产出的可交付位置：合成的音频、换口型的视频。
    output_url varchar(2000),

    status varchar(20) NOT NULL DEFAULT 'SUBMITTED',
    error_category varchar(64),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    terminal_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_audio_task_upstream UNIQUE (provider_id, upstream_task_id),

    CONSTRAINT ck_audio_task_dimension CHECK (usage_dimension = 'AUDIO_SECOND'),

    CONSTRAINT ck_audio_task_kind
        CHECK (kind IN ('TRANSCRIPTION', 'SYNTHESIS', 'LIP_SYNC')),

    -- 上游任务 id 的方向性：只有异步能力可以有它。
    -- 一条转写行带 task id 说明写入方把它当成了异步能力，而转写是同步的；
    -- 那条行永远不会被轮询，因此它的预留会一直挂到过期才被回收。
    CONSTRAINT ck_audio_task_upstream_kind CHECK (
        (kind = 'LIP_SYNC')
        OR (kind <> 'LIP_SYNC' AND upstream_task_id IS NULL)),

    CONSTRAINT ck_audio_task_status
        CHECK (status IN ('SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),

    -- 终态与结算字段必须同时出现或同时缺席。这条与两张姊妹表同形：
    -- 它让「已终态但没有结算秒数」和「未终态却已结算」都无法存在。
    CONSTRAINT ck_audio_task_values CHECK (
        requested_seconds > 0
        AND reserved_seconds > 0
        AND (billed_seconds IS NULL
             OR (billed_seconds >= 0 AND billed_seconds <= requested_seconds))
        AND (status IN ('SUBMITTED', 'RUNNING'))
            = (terminal_at IS NULL AND billed_seconds IS NULL)),

    -- **只有成功的行可以携带产物。** 失败的行若仍持有结果，一次
    -- Idempotency-Key 重放就会把它交付给客户端——即把一次失败卖成一次成功。
    -- 这与 ck_image_task_result、ck_video_task_output_url 是同一条保证。
    --
    -- 反向不要求「成功必有产物」：一次成功但厂商没交回可交付内容的调用
    -- 会在服务层按协议失败处理并**不**在这张表上落地，因此允许空。
    --
    -- 两个产物列各有其用，而这条约束**两个都看住**：
    --   result_payload —— 同步能力的结构化结果（转写的文本与时间戳）
    --   output_url     —— 产出的可交付位置（合成的音频、换口型的视频）
    -- 漏掉 output_url 会让一条失败的行带着一个可下载的位置，而重放正好会交付它。
    CONSTRAINT ck_audio_task_result CHECK (
        status = 'SUCCEEDED'
        OR (result_payload IS NULL AND output_url IS NULL)),

    -- 一次调用只能被重放到它的截止时刻；它持有的预留之后才过期。
    CONSTRAINT ck_audio_task_expiry CHECK (expires_at > submitted_at)
);

CREATE INDEX ix_audio_task_user_created
    ON gateway.audio_generation_task (user_id, created_at DESC);
-- 恢复扫描只关心还没到终态的行。三种音频能力都要扫，
-- 因为一次同步调用同样可能死在「上游已接单、网关没等到响应」之间。
CREATE INDEX ix_audio_task_open
    ON gateway.audio_generation_task (status, expires_at)
    WHERE status IN ('SUBMITTED', 'RUNNING');
CREATE INDEX ix_audio_task_reservation
    ON gateway.audio_generation_task (reservation_id);

COMMENT ON TABLE gateway.audio_generation_task IS
    'Idempotency anchor for the audio surface (POST /v1/audio/recognitions, '
    'POST /v1/audio/syntheses, POST /v1/video-lipsyncs). request_id is the client '
    'Idempotency-Key when supplied, otherwise the gateway trace id; a replay '
    'returns the stored result_payload instead of buying a second paid call. '
    'reserved_seconds is the maximum the package was debited before the upstream '
    'call; billed_seconds is what settlement actually charged.';
COMMENT ON COLUMN gateway.audio_generation_task.kind IS
    'TRANSCRIPTION | SYNTHESIS | LIP_SYNC. Determines whether the row may carry '
    'an upstream_task_id (LIP_SYNC only) and what shape result_payload holds.';
COMMENT ON COLUMN gateway.audio_generation_task.result_payload IS
    'Synchronous result mirror: the timestamped segments for TRANSCRIPTION, the '
    'audio location for SYNTHESIS. NULL on a SUCCEEDED row means the payload was '
    'too large to mirror (see AudioGenerationService.MAX_STORED_RESULT_BYTES).';
COMMENT ON COLUMN gateway.audio_generation_task.billed_seconds IS
    'Audio seconds actually charged. For TRANSCRIPTION these are the seconds of '
    'INPUT audio the vendor measured; for SYNTHESIS and LIP_SYNC they are the '
    'seconds of OUTPUT the vendor produced. The two directions are not '
    'interchangeable, which is why the dimension is per-second either way.';

-- ---------------------------------------------------------------------------
-- 断言
--
-- 与 V81/V96/V97 同一做法：直接写入受约束表的迁移必须自己证明写入合法。
-- 这里的重点是三个方向性约束——维度、kind 与上游 task id、终态与产物——
-- 因为它们每一个都只在**被违反时**才重要，而那时已经是一笔错的账。
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    dimension_definition text;
    kind_definition    text;
    upstream_definition text;
    result_definition  text;
    probe_constraint record;
BEGIN
    -- 本表必须真的把维度钉在 AUDIO_SECOND 上，而不是抄一份可变的词表。
    SELECT pg_get_constraintdef(constraint_row.oid) INTO dimension_definition
    FROM pg_constraint constraint_row
    WHERE constraint_row.conrelid = 'gateway.audio_generation_task'::regclass
      AND constraint_row.conname = 'ck_audio_task_dimension';

    IF dimension_definition IS NULL
            OR position('AUDIO_SECOND' IN dimension_definition) = 0 THEN
        RAISE EXCEPTION
            'audio_task_dimension_must_be_pinned_to_AUDIO_SECOND:definition=%',
            dimension_definition;
    END IF;

    -- kind 必须恰好认三个能力，一个不多一个不少。
    SELECT pg_get_constraintdef(constraint_row.oid) INTO kind_definition
    FROM pg_constraint constraint_row
    WHERE constraint_row.conrelid = 'gateway.audio_generation_task'::regclass
      AND constraint_row.conname = 'ck_audio_task_kind';

    IF kind_definition IS NULL
            OR position('TRANSCRIPTION' IN kind_definition) = 0
            OR position('SYNTHESIS' IN kind_definition) = 0
            OR position('LIP_SYNC' IN kind_definition) = 0 THEN
        RAISE EXCEPTION 'audio_task_kind_check_missing_a_kind:definition=%', kind_definition;
    END IF;

    -- 上游任务 id 的方向性约束必须存在：只有异步能力可以有它。
    SELECT pg_get_constraintdef(constraint_row.oid) INTO upstream_definition
    FROM pg_constraint constraint_row
    WHERE constraint_row.conrelid = 'gateway.audio_generation_task'::regclass
      AND constraint_row.conname = 'ck_audio_task_upstream_kind';

    IF upstream_definition IS NULL
            OR position('LIP_SYNC' IN upstream_definition) = 0 THEN
        RAISE EXCEPTION
            'audio_task_upstream_task_id_direction_check_missing:definition=%',
            upstream_definition;
    END IF;

    -- 产物约束必须存在：失败的行不得携带可交付结果。
    SELECT pg_get_constraintdef(constraint_row.oid) INTO result_definition
    FROM pg_constraint constraint_row
    WHERE constraint_row.conrelid = 'gateway.audio_generation_task'::regclass
      AND constraint_row.conname = 'ck_audio_task_result';

    IF result_definition IS NULL
            OR position('SUCCEEDED' IN result_definition) = 0 THEN
        RAISE EXCEPTION 'audio_task_result_check_missing:definition=%', result_definition;
    END IF;

    -- 行为检查：把本表自己的约束表达式挂到一张探针表上，用真实的 INSERT
    -- 让约束自己判断，而不是复述它的文本。
    --
    -- **为什么不直接往本表插一行。** 它有三个外键
    -- （quota_reservation、provider、channel），而一次探针 INSERT 在夹具缺失时
    -- 会以 23503（外键）失败而不是 23514（CHECK），于是那条断言会以一句
    -- 与它要证明的东西无关的错误报告出来。探针表没有外键，也没有触发器，
    -- 因此它只测这一件事：这些约束表达式对给定的列取值说什么。
    CREATE TEMPORARY TABLE audio_task_probe (
        usage_dimension varchar(20),
        kind varchar(24),
        upstream_task_id varchar(256),
        result_payload jsonb,
        output_url varchar(2000),
        status varchar(20),
        terminal_at timestamptz,
        billed_seconds integer,
        requested_seconds integer,
        reserved_seconds integer,
        expires_at timestamptz,
        submitted_at timestamptz
    ) ON COMMIT DROP;

    -- 把本表**全部** CHECK 约束挂到探针表上，而不是手工列举其中几条。
    --
    -- 手工列举已经错过了两次：先是漏了维度（"反向三"因此永远不可能触发
    -- check_violation，断言以它自己的 RAISE 失败，报告成"维度约束太松"），
    -- 再是漏了 ck_audio_task_values（"反向四"同样失败）。这个错误的形态是
    -- **一条断言永远无法通过**，而它的失败信息指向被断言的性质、不指向
    -- 漏挂的约束，所以每次都很难看出真正的原因。
    --
    -- 逐个遍历约束定义还让下面的断言自动覆盖将来新增的约束：这张表多一条
    -- CHECK，探针就多一条，不需要有人记得回来改这里。
    FOR probe_constraint IN
        SELECT constraint_row.conname AS name,
               pg_get_constraintdef(constraint_row.oid) AS definition
        FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'gateway.audio_generation_task'::regclass
          AND constraint_row.contype = 'c'
        ORDER BY constraint_row.conname
    LOOP
        EXECUTE format('ALTER TABLE audio_task_probe ADD CONSTRAINT %I %s',
                'probe_' || probe_constraint.name, probe_constraint.definition);
    END LOOP;

    -- 正向：一个未终态的、无产物的转写行必须被接受；一个成功的、
    -- 带产物的换口型行也必须被接受。没有正向用例，
    -- 下面两条反向断言在一个「总是拒绝」的约束下同样会通过。
    BEGIN
        INSERT INTO audio_task_probe(
            usage_dimension, kind, status, requested_seconds, reserved_seconds,
            submitted_at, expires_at)
        VALUES ('AUDIO_SECOND', 'TRANSCRIPTION', 'SUBMITTED', 5, 5,
                now(), now() + interval '30 minutes');
    EXCEPTION
        WHEN check_violation THEN
            RAISE EXCEPTION
                'a_submitted_transcription_row_was_rejected_by_its_own_constraints';
    END;

    BEGIN
        INSERT INTO audio_task_probe(
            usage_dimension, kind, upstream_task_id, output_url, status,
            requested_seconds, reserved_seconds, terminal_at, billed_seconds,
            submitted_at, expires_at)
        VALUES ('AUDIO_SECOND', 'LIP_SYNC', 't-1', 'https://cdn.example.test/out.mp4',
                'SUCCEEDED', 8, 8, now(), 8,
                now(), now() + interval '30 minutes');
    EXCEPTION
        WHEN check_violation THEN
            RAISE EXCEPTION
                'a_succeeded_lip_sync_row_was_rejected_by_its_own_constraints';
    END;

    -- 反向一：一个未终态的行不得携带产物。若写入方在提交时就把结果写下来，
    -- 一次 Idempotency-Key 重放就会把一次尚未完成的调用交付给客户端。
    BEGIN
        INSERT INTO audio_task_probe(
            usage_dimension, kind, status, requested_seconds, reserved_seconds,
            result_payload, submitted_at, expires_at)
        VALUES ('AUDIO_SECOND', 'TRANSCRIPTION', 'SUBMITTED', 5, 5,
                '{"text":"probe"}'::jsonb, now(), now() + interval '30 minutes');
        RAISE EXCEPTION 'a_submitted_audio_row_was_allowed_to_carry_a_result';
    EXCEPTION
        WHEN check_violation THEN
            NULL; -- 期望路径。
        WHEN raise_exception THEN
            RAISE;
    END;

    -- 反向二：同步能力不得携带上游 task id。一条带 task id 的转写行
    -- 永远不会被轮询，因此它的预留会一直挂到过期才被回收。
    BEGIN
        INSERT INTO audio_task_probe(
            usage_dimension, kind, upstream_task_id, status,
            requested_seconds, reserved_seconds, submitted_at, expires_at)
        VALUES ('AUDIO_SECOND', 'TRANSCRIPTION', 't-should-not-exist', 'SUBMITTED',
                5, 5, now(), now() + interval '30 minutes');
        RAISE EXCEPTION 'a_synchronous_audio_row_was_allowed_to_carry_a_task_id';
    EXCEPTION
        WHEN check_violation THEN
            NULL; -- 期望路径。
        WHEN raise_exception THEN
            RAISE;
    END;

    -- 反向三：维度不得是别的东西。抄错维度会让一行音频用量落进视频或图片计数器。
    BEGIN
        INSERT INTO audio_task_probe(
            usage_dimension, kind, status, requested_seconds, reserved_seconds,
            submitted_at, expires_at)
        VALUES ('VIDEO_SECOND', 'TRANSCRIPTION', 'SUBMITTED', 5, 5,
                now(), now() + interval '30 minutes');
        RAISE EXCEPTION 'an_audio_row_was_allowed_to_claim_the_video_dimension';
    EXCEPTION
        WHEN check_violation THEN
            NULL; -- 期望路径。
        WHEN raise_exception THEN
            RAISE;
    END;

    -- 反向四：一个未终态的行不得带着结算秒数。终态与结算必须同时出现，
    -- 否则「已预留但未结算」与「已结算但未终态」这两种状态都能存在，
    -- 而恢复扫描只能处理其中一种。
    BEGIN
        INSERT INTO audio_task_probe(
            usage_dimension, kind, status, requested_seconds, reserved_seconds,
            billed_seconds, submitted_at, expires_at)
        VALUES ('AUDIO_SECOND', 'SYNTHESIS', 'SUBMITTED', 5, 5, 3,
                now(), now() + interval '30 minutes');
        RAISE EXCEPTION 'a_submitted_audio_row_was_allowed_to_carry_billed_seconds';
    EXCEPTION
        WHEN check_violation THEN
            NULL; -- 期望路径。
        WHEN raise_exception THEN
            RAISE;
    END;
END $$;
