-- Video Economics Evidence Dimension
--
-- Adds a usage dimension and a dimension-qualified unit count to the gateway's usage
-- evidence, so a video settlement can be written down honestly: 8 settled SECONDS with
-- zero tokens, instead of 8 seconds filed as output_tokens.
--
-- Why: the chat path writes usage_log + usage_stage + provider_cost_record and enqueues an
-- economics event (UsageStageWriter), which is what platform.reporting turns into an
-- operating_fact. The video settle path could not join it: ck_usage_stage_type admitted
-- only the two chat stage types, the tables had no dimension column, and the economics
-- event had nowhere to say "these numbers are seconds". Writing the seconds into the token
-- counters was the only shape the ingestion contract accepted, and it corrupted the token
-- reports. See docs/video-economics-evidence-gap.md.
--
-- Convention (gateway V40/V41/V42 for package quotas, platform V54): the quantity column
-- is dimension-carrying and usage_dimension names the unit —
--   TOKEN          tokens   (usage_units = total_tokens)
--   VIDEO_SECOND   seconds  (token counters MUST be 0)
--   IMAGE          images   (token counters MUST be 0)
--
-- DEPLOYMENT ORDER: the platform (operating_fact dimension + USAGE_ECONOMICS_V3 ingestion)
-- MUST be deployed BEFORE this gateway emits V3 events. Ingestion rejects an unknown event
-- version as INVALID and audits it, so a gateway that ships first produces rejected video
-- events — fail-closed — rather than seconds read as tokens. Never reorder these two
-- deploys.

-- ---------------------------------------------------------------------------
-- 1. usage_log: the dimension of a usage row, and its qualified unit count
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.usage_log
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
    ADD COLUMN usage_units bigint NOT NULL DEFAULT 0;

-- Every row written before this migration is a token row: TOKEN was the only dimension the
-- economics contract admitted.
UPDATE gateway.usage_log
SET usage_units = total_tokens
WHERE usage_units <> total_tokens;

ALTER TABLE gateway.usage_log
    ADD CONSTRAINT ck_usage_log_usage_dimension CHECK (
        usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')),
    -- The unit count and the token counters may never disagree about the unit.
    --
    -- A non-TOKEN row is the direction that matters: its quantity lives in usage_units and
    -- its token counters must be zero, so a second can never be summed as a token.
    --
    -- A TOKEN row may leave usage_units unstated (0), in which case the row's quantity is
    -- its token total — which is what every TOKEN reader already uses — but it may never
    -- STATE a value other than total_tokens. Stating 0 with non-zero tokens is the one
    -- tolerated case, and it means "not stated": backfills and fixtures that predate this
    -- column insert token rows without it, and rejecting them would reject the historical
    -- evidence rather than the mislabelling this migration exists to prevent. Every row
    -- written by UsageStageWriter or VideoEconomicsEvidenceWriter states it explicitly.
    ADD CONSTRAINT ck_usage_log_usage_units CHECK (
        usage_units >= 0
        AND (usage_dimension <> 'TOKEN'
                OR usage_units = 0
                OR usage_units = total_tokens)
        AND (usage_dimension = 'TOKEN'
                OR (input_tokens = 0 AND output_tokens = 0 AND total_tokens = 0)));

-- Target for the composite foreign key below: a stage must carry the same dimension as the
-- usage row it belongs to, so a per-second stage can never hang off a token log.
ALTER TABLE gateway.usage_log
    ADD CONSTRAINT uq_usage_log_id_dimension UNIQUE (id, usage_dimension);

COMMENT ON COLUMN gateway.usage_log.usage_dimension IS
    'Unit of usage_units for this row: TOKEN (tokens), VIDEO_SECOND (seconds) or IMAGE '
    '(images). The economics event (USAGE_ECONOMICS_V3) carries it to the platform, which '
    'keeps the token and non-token invariants separately.';
COMMENT ON COLUMN gateway.usage_log.usage_units IS
    'Quantity consumed in usage_dimension. TOKEN: equal to total_tokens when stated. '
    'VIDEO_SECOND: the settled seconds, with input_tokens/output_tokens/total_tokens all 0.';

-- ---------------------------------------------------------------------------
-- 2. usage_stage: the same dimension, plus the video stage type
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.usage_stage
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
    ADD COLUMN usage_units bigint NOT NULL DEFAULT 0;

UPDATE gateway.usage_stage
SET usage_units = total_tokens
WHERE usage_units <> total_tokens;

-- V13 admitted only the two chat stages, so a video generation had no legal row to live
-- in even if a writer had wanted to file one.
ALTER TABLE gateway.usage_stage
    DROP CONSTRAINT ck_usage_stage_type,
    ADD CONSTRAINT ck_usage_stage_type CHECK (
        stage_type IN ('FINAL_RESPONSE', 'FILE_ANALYSIS', 'VIDEO_GENERATION')),
    ADD CONSTRAINT ck_usage_stage_usage_dimension CHECK (
        usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')),
    ADD CONSTRAINT ck_usage_stage_usage_units CHECK (
        usage_units >= 0
        AND (usage_dimension <> 'TOKEN'
                OR usage_units = 0
                OR usage_units = total_tokens)
        AND (usage_dimension = 'TOKEN'
                OR (input_tokens = 0 AND output_tokens = 0 AND total_tokens = 0))),
    -- Structurally, not by convention: a stage cannot claim a different dimension than its
    -- usage row, because the pair is a foreign key.
    --
    -- VALIDATED, matching what this schema actually carries. An earlier revision of this
    -- file declared it NOT VALID to tolerate legacy stage rows whose parent usage_log no
    -- longer exists; that revision was applied to the live database in its validated form
    -- and the live data satisfies it (zero orphan stage rows, zero dimension mismatches,
    -- verified before this file was settled), so the tolerant form was never needed here.
    -- Keeping the file aligned with the stricter applied constraint also means a fresh
    -- container gets the same guarantee the running one has, instead of enforcing the
    -- invariant only on rows written after the migration.
    ADD CONSTRAINT fk_usage_stage_usage_dimension
        FOREIGN KEY (usage_log_id, usage_dimension)
        REFERENCES gateway.usage_log (id, usage_dimension) ON DELETE RESTRICT;

COMMENT ON COLUMN gateway.usage_stage.usage_dimension IS
    'Unit of usage_units for this stage. The composite foreign key '
    'fk_usage_stage_usage_dimension forces it to equal the parent usage_log''s dimension.';
COMMENT ON COLUMN gateway.usage_stage.usage_units IS
    'Quantity consumed in usage_dimension. A VIDEO_GENERATION stage carries the billed '
    'seconds here with every token counter at zero.';
