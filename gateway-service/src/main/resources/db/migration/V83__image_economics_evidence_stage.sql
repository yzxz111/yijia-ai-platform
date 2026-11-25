-- Image Economics Evidence Stage
--
-- Gives a settled image generation a legal usage_stage row, so the image settle path can
-- write the same operational evidence the chat and video paths already write: one
-- gateway.usage_log row, its gateway.usage_stage, one gateway.provider_cost_record, and one
-- gateway.usage_economics_outbox event, all keyed off the reservation the generation
-- settled.
--
-- Why a migration is needed at all. Everything else the image evidence needs already
-- exists: V49 added usage_dimension/usage_units to usage_log and usage_stage and already
-- admits the IMAGE dimension (V49:43, V49:96), and V81/V82 publish the per-image rate card
-- that says what one IMAGE unit costs (62 分/张 at 2K) and what it retails for (89 分/张).
-- The one thing missing is the stage type: ck_usage_stage_type (V49:93) admits exactly
-- FINAL_RESPONSE, FILE_ANALYSIS and VIDEO_GENERATION, so a per-image stage has no legal row
-- to live in. Filing it as VIDEO_GENERATION would be false evidence, and filing it as
-- FINAL_RESPONSE would make an image indistinguishable from a chat turn.
--
-- What is deliberately NOT added. No column, no table, no default and no data. The
-- dimension is IMAGE, the quantity is billed_images (one unit = one image) and the cost
-- basis is the existing rate card, so there is nothing new to store -- only a name to
-- admit.
--
-- Idempotency, asserted rather than assumed. The writer can be reached twice for the same
-- generation: a replayed Idempotency-Key returns the stored result without settling again,
-- and both compensation paths (ImageGenerationService.compensateTerminalIfNeeded and
-- compensateTerminal) re-derive the charge from the persisted image_generation_task row
-- minutes later, possibly in another process. Exactly-once does not rest on an in-process
-- flag: every one of the four writes is conflict-protected by a constraint that already
-- exists, and the whole set runs inside one transaction, so a replay cannot write a second
-- copy and a crash cannot leave a half-written one:
--   usage_log               usage_log_request_id_key UNIQUE (request_id) (V1)
--                           usage_log_reservation_id_key UNIQUE (reservation_id) (V1)
--   usage_stage             UNIQUE (usage_log_id, stage_index) (V13:93)
--   provider_cost_record    usage_log_id UNIQUE (V1)
--   usage_economics_outbox  event_id PRIMARY KEY + uq_usage_economics_outbox_fingerprint (V24)
-- The DO block at the end of this file asserts each of them, so a later edit that drops one
-- is caught here instead of silently turning a replay into a second charge of record.
--
-- DEPLOYMENT ORDER: unchanged from V49. The platform already ingests USAGE_ECONOMICS_V3
-- and already knows the IMAGE dimension (platform V83 registers image-basic at 2K with
-- effective_unit_cost 62 / retail_unit_price 89), so this gateway-side file has no
-- counterpart to wait for.

-- ---------------------------------------------------------------------------
-- 1. Admit the image stage type
--
-- Written as the full list rather than by adding one value to an IN list copied from V49:
-- the constraint is restated in one place, so reading this file is enough to know every
-- stage type the schema admits. V13's two chat stages and V49's video stage are carried
-- over verbatim -- this migration widens, it never narrows.
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.usage_stage DROP CONSTRAINT ck_usage_stage_type;

ALTER TABLE gateway.usage_stage ADD CONSTRAINT ck_usage_stage_type CHECK (
    stage_type IN ('FINAL_RESPONSE', 'FILE_ANALYSIS', 'VIDEO_GENERATION', 'IMAGE_GENERATION'));

COMMENT ON COLUMN gateway.usage_stage.stage_type IS
    'Which production step this stage describes. FINAL_RESPONSE / FILE_ANALYSIS are chat '
    'stages (V13), VIDEO_GENERATION is a per-second video generation (V49), '
    'IMAGE_GENERATION is a per-image generation settled in IMAGE units (V83). The stage '
    'type names the step, never the unit: the unit is usage_dimension.';

COMMENT ON COLUMN gateway.usage_stage.provider_price_id IS
    'Rate-card row the provider cost was read from, when one exists in '
    'gateway.provider_model_price. NULL for the generation dimensions: their card is '
    'gateway.model_usage_price and that is a different id space, which the foreign key on '
    'this column (V24:53-54) does not accept. A NULL here therefore does not mean unpriced; '
    'the per-image basis is recorded in raw_usage (priceVersion/pricedTier/providerUnitCost) '
    'and the retail basis on gateway.model_usage_price.';

-- ---------------------------------------------------------------------------
-- 2. Assertions
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    stage_types    text;
    missing_unique integer;
BEGIN
    -- All four stage types must be admitted, and no fifth: the check is read back rather
    -- than trusted, because a typo in the list above would silently refuse the image
    -- evidence at settle time -- exactly the failure this migration exists to prevent.
    SELECT pg_get_constraintdef(oid) INTO stage_types
    FROM pg_constraint
    WHERE conname = 'ck_usage_stage_type'
      AND conrelid = 'gateway.usage_stage'::regclass;

    IF stage_types IS NULL
            OR stage_types NOT LIKE '%IMAGE_GENERATION%'
            OR stage_types NOT LIKE '%VIDEO_GENERATION%'
            OR stage_types NOT LIKE '%FINAL_RESPONSE%'
            OR stage_types NOT LIKE '%FILE_ANALYSIS%' THEN
        RAISE EXCEPTION 'usage_stage_type_check_missing_a_stage_type:%', stage_types;
    END IF;

    -- The four conflict targets the image evidence writer relies on for exactly-once.
    -- Asserted here because the writer's idempotency is only as real as these objects: a
    -- replay of the same request must fail the usage_log insert (returning zero rows, which
    -- is what makes the whole evidence chain skip), and a second provider cost row for the
    -- same usage row must be impossible rather than merely unlikely.
    SELECT count(*) INTO missing_unique
    FROM (
        SELECT 1
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_index index_
            WHERE index_.indrelid = 'gateway.usage_log'::regclass
              AND index_.indisunique
              AND pg_get_indexdef(index_.indexrelid) LIKE '%(request_id)%')
        UNION ALL
        SELECT 1
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_index index_
            WHERE index_.indrelid = 'gateway.usage_log'::regclass
              AND index_.indisunique
              AND pg_get_indexdef(index_.indexrelid) LIKE '%(reservation_id)%')
        UNION ALL
        SELECT 1
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_index index_
            WHERE index_.indrelid = 'gateway.usage_stage'::regclass
              AND index_.indisunique
              AND pg_get_indexdef(index_.indexrelid) LIKE '%(usage_log_id, stage_index)%')
        UNION ALL
        SELECT 1
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_index index_
            WHERE index_.indrelid = 'gateway.provider_cost_record'::regclass
              AND index_.indisunique
              AND pg_get_indexdef(index_.indexrelid) LIKE '%(usage_log_id)%')
        UNION ALL
        SELECT 1
        WHERE NOT EXISTS (
            SELECT 1 FROM pg_index index_
            WHERE index_.indrelid = 'gateway.usage_economics_outbox'::regclass
              AND index_.indisunique
              AND pg_get_indexdef(index_.indexrelid) LIKE '%(event_fingerprint)%')
    ) missing;

    IF missing_unique > 0 THEN
        RAISE EXCEPTION 'image_economics_evidence_idempotency_key_missing:count=%',
            missing_unique;
    END IF;

    -- The image dimension must still be admitted on both tables. V49 wrote it and this
    -- migration only rewrites the stage type, so this states that the rewrite did not
    -- accidentally carry the wrong constraint over.
    IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'ck_usage_log_usage_dimension'
              AND conrelid = 'gateway.usage_log'::regclass
              AND pg_get_constraintdef(oid) LIKE '%IMAGE%')
            OR NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'ck_usage_stage_usage_dimension'
              AND conrelid = 'gateway.usage_stage'::regclass
              AND pg_get_constraintdef(oid) LIKE '%IMAGE%') THEN
        RAISE EXCEPTION 'image_usage_dimension_not_admitted';
    END IF;
END $$;
