-- ---------------------------------------------------------------------------
-- Fan-out support: one POST /v1/images/generations may buy more than one image.
--
-- Measured 2026-10-12 against the live upstream (doubao-seedream-5.0-pro through
-- ai.ctaigw.cn): the vendor ACCEPTS the OpenAI `n` parameter and silently
-- IGNORES it. `n=2` and `n=4` both answered HTTP 200 with exactly one image and
-- `usage.generated_images = 1`, for text-to-image AND image-to-image alike
-- (see scripts/Probe-UpstreamImageCount.ps1). Passing `n` through is therefore
-- not merely useless, it is dangerous: the customer pays for N images and
-- receives one, with no error to branch on.
--
-- The only way to deliver N images is for the gateway to fan out N concurrent
-- vendor calls (ImageGenerationService.MAX_FANOUT) and aggregate them.
--
-- Two constraints forced the shape of that implementation:
--
--   * `image_generation_task.request_id` is UNIQUE (V81), and
--   * both `usage_log.reservation_id` (V1:275) and
--     `quota_ledger (reservation_id, entry_type)` (V1:231) are UNIQUE.
--
-- Together they mean N images cannot be one task row sharing one reservation.
-- The first forces N rows. The second forbids those rows from sharing a single
-- reservation: every charged generation writes its own usage_log row and its own
-- RESERVE/SETTLE ledger entries, so two generations on one reservation would
-- collide on insert. Each image therefore needs its OWN reservation — which is
-- exactly what per-image settlement and per-image refund need: one image
-- reserved, settled when it succeeds, released when it fails, so partial success
-- requires no invented "partial refund" logic at all.
--
-- Note that `image_generation_task.reservation_id` itself is deliberately NOT
-- unique (V81:181 only builds a plain lookup index). The uniqueness that matters
-- lives downstream in the ledger, which is why the assertions at the end of this
-- migration check those tables and not this one.
--
-- What the N rows were missing is a way to be found again. A replay of the same
-- client Idempotency-Key must return the same images, and the 8-minute recovery
-- sweep (ImageGenerationService.TASK_TTL) must be able to see the whole family.
-- These two columns are that link.
--
-- fanout_parent_request_id is NULL for every pre-existing row and for every
-- single-image request, so nothing about the n=1 path changes. `fanout_index` is
-- the 1-based position of this image inside its parent request; it orders the
-- aggregated `data[]` array and names the derived child request id
-- ("<parent>#<index>").
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.image_generation_task
    ADD COLUMN fanout_parent_request_id varchar(128),
    ADD COLUMN fanout_index integer;

-- Only the children of one parent are ever read together, and always in order.
CREATE INDEX ix_image_task_fanout_parent
    ON gateway.image_generation_task (fanout_parent_request_id, fanout_index)
    WHERE fanout_parent_request_id IS NOT NULL;

COMMENT ON COLUMN gateway.image_generation_task.fanout_parent_request_id IS
    'The client-supplied request id (Idempotency-Key) of the fan-out request '
    'this row is one image of. NULL for every single-image request and for '
    'every row that predates gateway V114. A replay groups by this column to '
    'return the same set of images instead of buying new ones.';

COMMENT ON COLUMN gateway.image_generation_task.fanout_index IS
    '1-based position of this image within its parent request. The derived '
    'child request id is "<fanout_parent_request_id>#<fanout_index>", which is '
    'how a retry finds the same row again. NULL exactly when '
    'fanout_parent_request_id is NULL.';

-- A fan-out child carries exactly one image: requested_images = reserved_images
-- = 1. The pre-existing ck_image_task_values already allows this (requested > 0
-- and billed <= requested), so no existing constraint needs to change. What is
-- new is that a NULL / NOT NULL mismatch must not be storable: half a link would
-- make a child unfindable by replay, which is the one thing a child must not be.
ALTER TABLE gateway.image_generation_task
    ADD CONSTRAINT ck_image_task_fanout_pair CHECK (
        (fanout_parent_request_id IS NULL AND fanout_index IS NULL)
        OR (fanout_parent_request_id IS NOT NULL
            AND fanout_index IS NOT NULL
            AND fanout_index >= 1));

-- ---------------------------------------------------------------------------
-- Assertions: the columns exist, every pre-existing row is NULL (so the n=1
-- path and the recovery sweep see exactly what they saw before), and the
-- uniqueness that forced per-image reservations is still in place.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    missing      integer;
    linked_rows  integer;
    unique_req   integer;
    unique_usage integer;
    unique_ledger integer;
BEGIN
    SELECT count(*) INTO missing
    FROM (VALUES ('fanout_parent_request_id'), ('fanout_index')) AS expected(column_name)
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'gateway'
          AND table_name = 'image_generation_task'
          AND column_name = expected.column_name);

    IF missing <> 0 THEN
        RAISE EXCEPTION 'image_fanout_columns_missing:%', missing;
    END IF;

    -- This migration runs against a populated table in production. Every row
    -- that exists at this moment is a single-image generation, so every one of
    -- them must stay unlinked.
    SELECT count(*) INTO linked_rows
    FROM gateway.image_generation_task
    WHERE fanout_parent_request_id IS NOT NULL OR fanout_index IS NOT NULL;

    IF linked_rows <> 0 THEN
        RAISE EXCEPTION 'image_fanout_backfill_unexpected:%', linked_rows;
    END IF;

    -- The uniqueness that dictates "one reservation per image" must still hold.
    -- If any of these is ever relaxed, the fan-out design's central assumption
    -- changed and this migration's reasoning no longer applies.
    --
    -- Asserted by name rather than by parsing DDL text: pg_get_indexdef omits
    -- the parentheses for a single-column index, so text matching is needlessly
    -- brittle. Names alone would still pass after a de-uniquifying, so
    -- indisunique / contype are checked too.
    SELECT count(*) INTO unique_req
    FROM pg_constraint
    WHERE conrelid = 'gateway.image_generation_task'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE '%(request_id)%';

    IF unique_req = 0 THEN
        RAISE EXCEPTION 'image_fanout_request_id_not_unique';
    END IF;

    SELECT count(*) INTO unique_usage
    FROM pg_index
    WHERE indrelid = 'gateway.usage_log'::regclass
      AND indisunique
      AND pg_get_indexdef(indexrelid) LIKE '%(reservation_id)%'
      AND pg_get_indexdef(indexrelid) NOT LIKE '%,%';

    IF unique_usage = 0 THEN
        RAISE EXCEPTION 'image_fanout_usage_log_reservation_id_not_unique';
    END IF;

    SELECT count(*) INTO unique_ledger
    FROM pg_constraint
    WHERE conrelid = 'gateway.quota_ledger'::regclass
      AND contype = 'u'
      AND pg_get_constraintdef(oid) LIKE '%(reservation_id, entry_type)%';

    IF unique_ledger = 0 THEN
        RAISE EXCEPTION 'image_fanout_ledger_reservation_not_unique';
    END IF;
END $$;
