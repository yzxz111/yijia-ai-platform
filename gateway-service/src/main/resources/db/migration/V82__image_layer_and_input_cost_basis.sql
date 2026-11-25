-- Image billing corrections: layer decomposition is one unit per CALL, and the
-- per-image cost basis must carry the input-image charge.
--
-- Two decisions from the product owner (2026-09-28), applied to the image
-- generation path V81__image_generation.sql introduced:
--
--   1. A layer-decomposition call is billed PER CALL, not per output layer.
--      V81 knew only one shape -- one image returned, one unit charged -- so a
--      layer call whose vendor reports several images settled several units for
--      a single paid vendor call. That over-charges the customer.
--   2. The image-to-image input image costs 2 分/张 at the vendor (¥0.02), and
--      that cost must be paid by the customer, not absorbed by the platform.
--      The rate card therefore moves from the output-only 60 分/张 basis to the
--      worst-case 62 分/张 that includes it.
--
-- Neither correction needs a new table or a new dimension: unit 1 is a column
-- on the existing idempotency anchor, unit 2 is a new row on the existing rate
-- card. No credential is seeded, no channel/provider/model row is touched, and
-- V81's price row is retired rather than edited (see section 3, which cites
-- uq_model_usage_price_current).

-- ---------------------------------------------------------------------------
-- 1. How a generation was asked for: the layer-decomposition flag
--
-- WHY THE CHARGE NEEDS THIS COLUMN AT ALL. The settlement runs off the stored
-- task row, not off the request that created it: completeSuccess() charges from
-- the row it just claimed, and both compensation paths -- a concurrent replay
-- (compensateTerminalIfNeeded) and a crash between the terminal write and the
-- debit (compensateTerminal) -- re-derive the same charge from that same row
-- minutes later, possibly in another process. A flag that lived only in memory
-- would therefore let the compensation path charge the per-layer count for a
-- call the winning path charged one unit for, which is the exact
-- over-charge this migration exists to stop. So the flag is persisted with the
-- submission intent, next to requested_images/reserved_images.
--
-- WHY A SEPARATE COLUMN AND NOT A REUSE OF AN EXISTING ONE. The row already
-- carries requested_images, reserved_images and resolution_tier, none of which
-- can express "this was a layer call": a layer call is still one request for one
-- image, and the tier describes pixels. Overloading one of them would make the
-- charge depend on a field whose documented meaning is something else.
--
-- NOT NULL DEFAULT false with no backfill: every row written before this
-- migration was submitted by a gateway that had no layer flag to record, and
-- all of them were charged per returned image. false is therefore the truthful
-- value for them -- a backfill UPDATE would only rewrite every historical row to
-- the value the default already gives it.
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.image_generation_task
    ADD COLUMN layer_decomposition boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN gateway.image_generation_task.layer_decomposition IS
    'Whether the caller asked for layer decomposition (拆图层). A layer call is '
    'ONE paid vendor call that happens to return several layers, so '
    'ImageGenerationService.chargedImages settles it as exactly 1 IMAGE unit '
    'regardless of n or of how many images the vendor reports; every other call '
    'is settled per image actually delivered (see V82).';

-- ---------------------------------------------------------------------------
-- 2. Restate what billed_images means now
--
-- V81 documented it as "the number of images the vendor returned, never above
-- requested_images". That sentence is what produced the over-charge: for a layer
-- call the vendor legitimately returns more than one image and the stored count
-- followed it. The column itself is unchanged -- only what may be written into it
-- is narrower -- so this is a comment, not a constraint. It cannot be a CHECK:
-- billed_images must stay <= requested_images for ordinary calls, and the layer
-- path is the one case where the count is deliberately decoupled from the
-- response, which is a decision the service makes (§ comment in chargedImages),
-- not a shape the row can distinguish on its own.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN gateway.image_generation_task.billed_images IS
    'IMAGE units actually charged (one unit = one image). Equals the number of '
    'images the vendor delivered, capped at requested_images; for a '
    'layer_decomposition call it is exactly 1, because that call is one paid '
    'vendor call however many layers it returns (see V82).';

-- ---------------------------------------------------------------------------
-- 3. Rate card: price the worst case, input image included
--
-- THE COST BASIS. The vendor bills the output render (¥0.30-0.60/张, ¥0.60 =
-- 60 分 being the worst case V81 stored) AND, for image-to-image, an extra input
-- image at ¥0.02/张 = 2 分. The product owner requires that second charge to be
-- passed through to the customer rather than absorbed, so the basis becomes the
-- worst case of both: 60 + 2 = 62 分/张. Storing 60 would leave every
-- image-to-image call 2 分 under water, and storing a blended average would lose
-- money on exactly the mix the platform cannot predict.
--
-- THE RETAIL PRICE. 89 分/张 (¥0.89) is the smallest integer that clears BOTH
-- floors V40 wrote as ck_model_usage_price_margin (V40:114-125):
--     absolute: unit_price * minimum_units - provider_unit_cost * minimum_units
--               >= min_absolute_margin  ->  price - 62 >= 25  ->  price >= 87
--     ratio:    unit_price >= ceil(provider_unit_cost / (1 - target_margin_ratio))
--               ->  when target_margin_ratio = 0.30: ceil(62 / 0.7) = ceil(88.571) = 89
-- 89 is therefore the price at which the customer pays the 2 分 input charge:
-- per-image profit is 89 - 62 = 27 分 (¥0.27) on the worst case, and it is higher
-- whenever a render bills below the 60 分 peak. 87 would satisfy the absolute
-- floor alone but sit at 28.7% margin, below the 30% the product guarantees for
-- non-token dimensions. minimum_units stays 1 (one image is one unit) and
-- retry_rate stays 0 (an image call is synchronous; a failed call returns no
-- image and the vendor does not bill it -- V81:204-210), so
-- gateway.image_effective_cost still resolves effective_unit_cost = 62 without
-- an allowance on top.
--
-- WHY THE OLD ROW IS CLOSED RATHER THAN UPDATED. uq_model_usage_price_current
-- (V40:137-139) is UNIQUE (model_code, usage_dimension, resolution_tier) WHERE
-- effective_to IS NULL, so a second current row for image-basic/IMAGE/2K cannot
-- be inserted at all. The house shape -- used by VideoAliasBillingService and
-- V46/V48/V50 for the video card -- is to close the current row by stamping
-- effective_to and insert the replacement under a new price_version. That also
-- keeps history honest: a charge already settled against image-v1 can still be
-- explained by the row it was settled against, which an in-place UPDATE would
-- erase. ck_model_usage_price_values (V40:92-100) requires effective_to >
-- effective_from, and 2026-09-28T00:00:00Z -- the moment image-v1 took effect --
-- is in the past, so this stamp is legal.
--
-- WHY 89 RATHER THAN 88 OR 87 IS ALSO WHAT ck_model_usage_price_floor WANTS:
-- minimum_charge is the smallest charge for this row and unit_price is the
-- per-unit price; V81 set both to 86, and both move to 89 together so the row
-- still describes "89 分 per image, never less".
-- ---------------------------------------------------------------------------

UPDATE gateway.model_usage_price
SET effective_to = now()
WHERE model_code = 'image-basic'
  AND usage_dimension = 'IMAGE'
  AND resolution_tier = '2K'
  AND price_version = 'image-v1'
  AND effective_to IS NULL;

INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    ('86000000-0000-4000-8000-000000000082', 'image-basic', 'IMAGE', '2K',
     1, 89, 89, 62, 0, 0.30000, 25, 'image-v2',
     now(),
     'product:doubao-seedream-5.0-pro output 0.30-0.60 CNY/image worst case '
     '+ input image 0.02 CNY/image, both passed through')
ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Assertions
--
-- Asserted rather than assumed, in V81's style: this migration rewrites the cost
-- basis the package publication guard prices every image version against, so it
-- must prove the numbers it wrote are the ones the guard will read. Each check
-- names the constraint or query it is protecting, because a silent failure here
-- is a wrong retail price, not a cosmetic problem.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    current_rows     integer;
    v1_open          integer;
    v2_row           record;
    cost             bigint;
    unit             bigint;
    missing_column   integer;
BEGIN
    -- The flag the charge decision depends on must exist and be NOT NULL: a NULL
    -- would read as "not a layer call" through a boxed Boolean and silently
    -- reintroduce the per-layer charge.
    SELECT count(*) INTO missing_column
    FROM information_schema.columns
    WHERE table_schema = 'gateway'
      AND table_name = 'image_generation_task'
      AND column_name = 'layer_decomposition'
      AND is_nullable = 'NO'
      AND column_default = 'false';

    IF missing_column <> 1 THEN
        RAISE EXCEPTION 'image_task_layer_decomposition_flag_missing';
    END IF;

    -- uq_model_usage_price_current: exactly one current row per
    -- (model_code, usage_dimension, resolution_tier). Two current rows would be
    -- rejected by the index -- this states the invariant so a future edit that
    -- drops the index is caught here instead.
    SELECT count(*) INTO current_rows
    FROM gateway.model_usage_price
    WHERE model_code = 'image-basic'
      AND usage_dimension = 'IMAGE'
      AND resolution_tier = '2K'
      AND effective_to IS NULL;

    IF current_rows <> 1 THEN
        RAISE EXCEPTION 'image_rate_card_must_have_exactly_one_current_row:count=%',
            current_rows;
    END IF;

    -- image-v1 must be closed, not deleted: it is the basis earlier charges were
    -- settled against.
    SELECT count(*) INTO v1_open
    FROM gateway.model_usage_price
    WHERE model_code = 'image-basic'
      AND usage_dimension = 'IMAGE'
      AND resolution_tier = '2K'
      AND price_version = 'image-v1'
      AND effective_to IS NOT NULL;

    IF v1_open <> 1 THEN
        RAISE EXCEPTION 'image_v1_must_be_retired_by_effective_to';
    END IF;

    SELECT price_version, provider_unit_cost, unit_price, minimum_units,
           minimum_charge, retry_rate, target_margin_ratio, min_absolute_margin
    INTO v2_row
    FROM gateway.model_usage_price
    WHERE model_code = 'image-basic'
      AND usage_dimension = 'IMAGE'
      AND resolution_tier = '2K'
      AND effective_to IS NULL;

    IF v2_row.price_version <> 'image-v2'
            OR v2_row.provider_unit_cost <> 62
            OR v2_row.unit_price <> 89
            OR v2_row.minimum_units <> 1
            OR v2_row.minimum_charge <> 89
            OR v2_row.retry_rate <> 0
            OR v2_row.target_margin_ratio <> 0.30000
            OR v2_row.min_absolute_margin <> 25 THEN
        RAISE EXCEPTION 'image_v2_rate_card_not_as_ruled:version=%:cost=%:price=%',
            v2_row.price_version, v2_row.provider_unit_cost, v2_row.unit_price;
    END IF;

    -- ck_model_usage_price_margin restated in the migration's own terms, so the
    -- arithmetic that chose 89 is visible and a later edit to either floor is
    -- caught rather than assumed to still hold.
    IF v2_row.unit_price < ceil(v2_row.provider_unit_cost::numeric
                / (1 - v2_row.target_margin_ratio))
            OR v2_row.unit_price * v2_row.minimum_units
                - v2_row.provider_unit_cost * v2_row.minimum_units
                < v2_row.min_absolute_margin THEN
        RAISE EXCEPTION 'image_v2_price_does_not_clear_the_margin_floors:price=%',
            v2_row.unit_price;
    END IF;

    -- gateway.image_effective_cost is the view ProviderPriceQueryRepository reads
    -- for /internal/prices/effective-unit-cost, which is what the platform's
    -- publication guard prices an image version against. It hard-filters
    -- effective_to IS NULL, so it must now resolve 62 -- not 60 (v1, still the
    -- open row if the stamp above failed) and not 124 (both rows, if the view
    -- ever stopped filtering).
    SELECT cost_view.effective_unit_cost, cost_view.unit_price
    INTO cost, unit
    FROM gateway.image_effective_cost cost_view
    WHERE cost_view.model_code = 'image-basic'
      AND cost_view.resolution_tier = '2K';

    IF cost IS NULL THEN
        RAISE EXCEPTION 'image_effective_cost_missing:image-basic';
    END IF;
    IF cost <> 62 OR unit <> 89 THEN
        RAISE EXCEPTION 'image_effective_cost_not_the_worst_case_basis:cost=%:price=%',
            cost, unit;
    END IF;
END $$;
