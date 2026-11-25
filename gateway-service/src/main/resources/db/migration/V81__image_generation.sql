-- Image Generation (Text-to-Image / Image-to-Image)
--
-- Adds a synchronous image-generation path for the 天翼云 AI 网关
-- (ai.ctaigw.cn) route whose real upstream is doubao-seedream-5.0-pro, billed
-- per IMAGE under the existing IMAGE usage dimension.
--
-- Unit: money columns are 分 (CNY cent); image prices are 分 per IMAGE (分/张).
--
-- Design: docs/superpowers/specs/2026-09-28-image-generation-and-mixed-pool-design.md
--
-- Prerequisites this migration builds on:
--   V40__usage_dimension_rate_card.sql  created gateway.model_usage_price with
--       usage_dimension IN ('TOKEN','VIDEO_SECOND','IMAGE') and pinned
--       resolution_tier to ('', '480P', '720P', '1080P').
--   V41__video_second_metering.sql      created gateway.video_generation_task
--       and gateway.video_effective_cost (hard-filtered to VIDEO_SECOND).
--   V54__video_alias_resolution_tier.sql created gateway.video_alias_tier.
--   V78__tianyi_video_provider_type.sql  added TIANYIYUN to ck_provider_type.
--
-- No credential is seeded. Following V71's precedent verbatim: the Tianyi image
-- channel is registered disabled with last_health_status 'UNKNOWN' and a NULL
-- encrypted_api_key. An operator stores the system-managed key once in
-- 上游渠道 / 模型管理, enables the channel and completes a health check. The
-- client-facing catalogue deliberately does not require those route conditions;
-- POST /v1/images/generations remains strict and cannot route these rows alone.

-- ---------------------------------------------------------------------------
-- 1. Rate card: image size tiers
--
-- WHY THIS WIDENING IS NEEDED. An image alias is priced per image at the size
-- tier it sells, and that tier is part of the rate card's identity:
-- uq_model_usage_price_current is (model_code, usage_dimension, resolution_tier).
-- V40's ck_model_usage_price_tier admits only ('', '480P', '720P', '1080P'), so
-- a 2K image row is not expressible at all -- there is nowhere to write the
-- cost basis the publication margin guard prices a package against.
--
-- WHAT IS *NOT* CHANGED. The rule is rewritten as an explicit per-dimension
-- disjunction rather than by widening one IN list, so the existing rules are
-- preserved exactly where they already were:
--   TOKEN       -> resolution_tier = ''                  (unchanged)
--   VIDEO_SECOND-> 480P / 720P / 1080P                   (unchanged)
--   IMAGE       -> 1K / 2K / 4K                          (new)
-- A video row still cannot claim an image tier and a token row still cannot
-- claim a tier at all, which is what stops a per-image price from being read as
-- a per-second one.
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.model_usage_price DROP CONSTRAINT ck_model_usage_price_tier;

ALTER TABLE gateway.model_usage_price ADD CONSTRAINT ck_model_usage_price_tier
    CHECK (
        (usage_dimension = 'TOKEN' AND resolution_tier = '')
        OR (usage_dimension = 'VIDEO_SECOND'
            AND resolution_tier IN ('480P', '720P', '1080P'))
        OR (usage_dimension = 'IMAGE'
            AND resolution_tier IN ('1K', '2K', '4K')));

COMMENT ON COLUMN gateway.model_usage_price.resolution_tier IS
    'Size tier the row prices. TOKEN: always ''''. VIDEO_SECOND: 480P / 720P / '
    '1080P. IMAGE: 1K / 2K / 4K (see V81). The tier is part of the rate card '
    'identity, so one alias may hold one row per tier and dimension.';

-- ---------------------------------------------------------------------------
-- 2. Image alias tier: the size each image alias sells
--
-- WHY. Video aliases are pinned to one resolution tier by V54 because a 480P
-- package driven at 1080P is a loss. Images repeat that shape: the alias carries
-- one price per image, quoted at the tier it sells, and the publication guard
-- computes a version's worst-case margin against that tier's cost. Without a
-- registered tier the gateway cannot know which size an alias was priced for,
-- and the fail-closed answer is to refuse the request rather than to render at
-- a size nobody priced.
--
-- SCOPE. Gateway copy of the mapping rather than a cross-schema read of
-- platform.model_usage_dimension: platform Flyway owns only the `platform`
-- schema, the gateway must answer this before it talks to a vendor, and a
-- cross-schema join would make a platform-only migration test fail. Same
-- reasoning as V54.
-- ---------------------------------------------------------------------------

CREATE TABLE gateway.image_alias_tier (
    model_code varchar(128) PRIMARY KEY
        REFERENCES gateway.model(code) ON DELETE CASCADE,
    resolution_tier varchar(16) NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_image_alias_tier_value
        CHECK (resolution_tier IN ('1K', '2K', '4K'))
);

COMMENT ON TABLE gateway.image_alias_tier IS
    'The one size tier each image generation alias sells. Enforced by '
    'ImageGenerationService: a request whose size resolves to a larger tier is '
    'refused with image_size_not_sold_by_model, and an alias with no row here is '
    'refused with image_model_size_unregistered. Mirrors '
    'platform.model_usage_dimension.resolution_tier, which the package catalogue '
    'prices an image version against.';

COMMENT ON COLUMN gateway.image_alias_tier.resolution_tier IS
    '1K / 2K / 4K. The largest size tier the alias may be asked for: the alias is '
    'priced per image at this tier, so serving it (or a smaller render) can never '
    'cost more than the basis the package was published against, while a larger '
    'render is the loss V54 exists to prevent.';

-- ---------------------------------------------------------------------------
-- 3. Image generation records
--
-- Image generation is SYNCHRONOUS upstream: one request, one response, so the
-- reservation and the settlement happen inside the same call and there is no
-- task to poll and no recovery scan to run. This table is therefore not a task
-- queue -- it is the idempotency anchor. Without it a client that retries after
-- a timeout buys a second paid generation, which is exactly the failure the
-- video path prevents with gateway.video_generation_task.
--
-- Modelled on V41's video_generation_task, with seconds replaced by images and
-- the vendor task id replaced by the returned payload, because a synchronous
-- call has no vendor-side handle to store.
-- ---------------------------------------------------------------------------

CREATE TABLE gateway.image_generation_task (
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
    resolution_tier varchar(16) NOT NULL,
    usage_dimension varchar(20) NOT NULL DEFAULT 'IMAGE',
    requested_images integer NOT NULL,
    reserved_images integer NOT NULL,
    billed_images integer,
    requested_size varchar(32) NOT NULL,
    response_format varchar(16) NOT NULL DEFAULT 'url',
    -- The OpenAI-shaped data array as returned by the vendor, so a replayed
    -- Idempotency-Key returns the same images instead of buying a second
    -- generation. NULL on a SUCCEEDED row means the payload was too large to
    -- mirror (see ImageGenerationService.MAX_STORED_RESULT_BYTES).
    result_data jsonb,
    result_images integer,
    status varchar(20) NOT NULL DEFAULT 'SUBMITTED',
    error_category varchar(64),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    terminal_at timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_image_task_dimension CHECK (usage_dimension = 'IMAGE'),
    CONSTRAINT ck_image_task_tier CHECK (resolution_tier IN ('1K', '2K', '4K')),
    CONSTRAINT ck_image_task_status
        CHECK (status IN ('SUBMITTED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_image_task_values CHECK (
        requested_images > 0
        AND reserved_images > 0
        AND (billed_images IS NULL
             OR (billed_images >= 0 AND billed_images <= requested_images))
        AND (status = 'SUBMITTED')
            = (terminal_at IS NULL AND billed_images IS NULL)),
    -- Only a successful generation may carry images. A failed row that still
    -- held a payload would be deliverable by a replay, which is the same
    -- guarantee ck_video_task_output_url gives the video path.
    CONSTRAINT ck_image_task_result CHECK (
        (status = 'SUCCEEDED'
            AND result_images IS NOT NULL AND result_images > 0
            AND (result_data IS NULL OR jsonb_typeof(result_data) = 'array'))
        OR (status <> 'SUCCEEDED'
            AND result_images IS NULL AND result_data IS NULL)),
    -- A generation may only be replayed until its deadline; the reservation it
    -- holds expires after it.
    CONSTRAINT ck_image_task_expiry CHECK (expires_at > submitted_at)
);

CREATE INDEX ix_image_task_user_created
    ON gateway.image_generation_task (user_id, created_at DESC);
CREATE INDEX ix_image_task_open
    ON gateway.image_generation_task (status, expires_at)
    WHERE status = 'SUBMITTED';
CREATE INDEX ix_image_task_reservation
    ON gateway.image_generation_task (reservation_id);

COMMENT ON TABLE gateway.image_generation_task IS
    'Idempotency anchor for POST /v1/images/generations. request_id is the '
    'client Idempotency-Key when one is supplied, otherwise the gateway trace id; '
    'a replay returns the stored result_data instead of buying a second paid '
    'generation. reserved_images is the maximum the package was debited before '
    'the upstream call; billed_images is what the settlement actually charged.';

COMMENT ON COLUMN gateway.image_generation_task.billed_images IS
    'Images actually charged, in IMAGE units (one unit = one image). Settled to '
    'the number of images the vendor returned, never above requested_images.';

-- ---------------------------------------------------------------------------
-- 4. Provider cost for an image
--
-- gateway.video_effective_cost is HARD-FILTERED to usage_dimension =
-- 'VIDEO_SECOND' (V41:125) and that semantics is deliberately left untouched:
-- its effective_unit_cost is 分 per SECOND and a consumer that read an image row
-- out of it would scale pixels as if they were seconds. The parallel view below
-- carries the image basis in its own unit, 分 per IMAGE (分/张).
--
-- The retry allowance is applied the same way as the video view (ceil of
-- provider_unit_cost * (1 + retry_rate)) so both dimensions are read by the
-- publication guard through one arithmetic shape. For the seeded Tianyi image
-- row retry_rate is 0: an image call is synchronous, and a call that fails
-- returns no image and is not billed by the vendor, so there is no failed-attempt
-- allowance to carry. That is the opposite of V40's video rows, where Kling
-- charges for failed generations and the rate is therefore 15%.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW gateway.image_effective_cost AS
SELECT price.model_code,
       price.resolution_tier,
       price.provider_unit_cost,
       price.retry_rate,
       price.min_absolute_margin,
       price.target_margin_ratio,
       price.unit_price,
       price.price_version,
       ceil(price.provider_unit_cost * (1 + price.retry_rate))::bigint AS effective_unit_cost
FROM gateway.model_usage_price price
WHERE price.usage_dimension = 'IMAGE'
  AND price.effective_to IS NULL;

COMMENT ON VIEW gateway.image_effective_cost IS
    'Current per-image effective provider cost for image generation aliases, in '
    '分 per IMAGE (分/张). Parallel to gateway.video_effective_cost, which is 分 '
    'per second and stays hard-filtered to VIDEO_SECOND. Read by '
    'ProviderPriceQueryRepository for /internal/prices/effective-unit-cost.';

-- ---------------------------------------------------------------------------
-- 5. The image generation alias
--
-- context_window / max_output_tokens cannot be NULL or zero: ck_model_limits
-- (V1:26) requires both to be positive. They describe nothing about a generator
-- -- there is no prompt/completion split and image billing never reads them --
-- so they are set to defensible defaults rather than to the minimum, matching
-- V40's video rows which carry 1/1 for the same reason.
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model(
    id, code, display_name, description, capabilities,
    context_window, max_output_tokens, premium, enabled,
    requests_per_minute, max_concurrency, routing_strategy, routing_policy)
VALUES
    ('82000000-0000-4000-8000-000000000110', 'image-basic', '图片基础版',
     '文生图 / 图生图，2K 档位，单张计费', '["TEXT","IMAGE","IMAGE_GEN"]',
     8192, 4096, false, true, 20, 4, 'DIRECT', '{}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO gateway.model_alias(id, model_id, alias)
SELECT '83000000-0000-4000-8000-000000000110', model.id, model.code
FROM gateway.model model
WHERE model.code = 'image-basic'
ON CONFLICT (alias) DO NOTHING;

INSERT INTO gateway.image_alias_tier(model_code, resolution_tier)
VALUES ('image-basic', '2K')
ON CONFLICT (model_code) DO UPDATE SET
    resolution_tier = EXCLUDED.resolution_tier,
    registered_at = now();

-- ---------------------------------------------------------------------------
-- 6. Tianyi image route (no credential)
--
-- Separate channel from V71's bailian-video-primary and from any Tianyi video
-- channel: the image endpoint is /v1/images/generations on this origin, while
-- the video adapter composes /v1/video_generation. provider_type TIANYIYUN is
-- already permitted by ck_provider_type (V78) and this migration does not alter
-- that constraint.
-- ---------------------------------------------------------------------------

INSERT INTO gateway.provider(id, code, name, provider_type, base_url, enabled)
VALUES
    ('81000000-0000-4000-8000-000000000078', 'tianyi', '天翼云', 'TIANYIYUN',
     'https://ai.ctaigw.cn', false)
ON CONFLICT (code) DO NOTHING;

INSERT INTO gateway.channel(
    id, provider_id, code, name, environment, base_url, enabled, priority, weight,
    timeout_ms, requests_per_minute, max_concurrency, last_health_status, settings)
SELECT
    '84000000-0000-4000-8000-000000000072', provider.id,
    'tianyi-image-primary', '天翼云生图主渠道', 'PRODUCTION',
    'https://ai.ctaigw.cn',
    false, 100, 100, 60000, 20, 4, 'UNKNOWN',
    '{"managed":true,"credentialOwner":"system-gateway"}'::jsonb
FROM gateway.provider provider
WHERE provider.code = 'tianyi'
  AND provider.provider_type = 'TIANYIYUN'
ON CONFLICT (code) DO NOTHING;

INSERT INTO gateway.channel_model(
    id, channel_id, model_id, upstream_model, enabled, capabilities)
SELECT route.mapping_id, channel.id, model.id, route.upstream_model, true,
       '["TEXT","IMAGE","IMAGE_GEN"]'::jsonb
FROM (VALUES
    ('85000000-0000-4000-8000-000000000076'::uuid,
        'image-basic', 'doubao-seedream-5.0-pro')
) AS route(mapping_id, model_code, upstream_model)
JOIN gateway.channel channel
  ON channel.code = 'tianyi-image-primary'
JOIN gateway.model model
  ON model.code = route.model_code
ON CONFLICT (channel_id, model_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7. Image rate card (分 per image)
--
-- Cost basis supplied by the product owner (2026-09-28): input image ¥0.02/张,
-- output image ¥0.30–0.60/张 weighted by resolution. provider_unit_cost is the
-- WORST-CASE output figure, ¥0.60/张 = 60 分/张, because
-- ck_model_usage_price_margin requires unit_price >= ceil(provider_unit_cost /
-- (1 - target_margin_ratio)) and the cost columns are documented as the peak /
-- worst-case basis (V40:52-56). Storing the cheap end would let the publication
-- guard approve a price that the 4K-weighted mix loses money on.
--
-- The retail input-image price (¥0.30/张) is a separate SKU and is deliberately
-- NOT folded into this row: it would overstate the per-image output cost by 50%
-- and price the output image at the input customer's expense. If it is sold, it
-- belongs in its own tier row with its own source, not in this cost basis.
--
-- Retail is 86 分/张 (¥0.86): exactly ceil(60 / (1 - 0.30)), the 30% floor the
-- gateway enforces for non-token dimensions (VideoAliasBillingService.MARGIN_FLOOR).
-- These numbers are the mirror of platform.model_usage_dimension (platform V83
-- registers image-basic at 2K with effective_unit_cost 60 and
-- retail_unit_price 86); the platform read model documents itself as a copy of
-- this rate card, so the two must be changed together.
-- ---------------------------------------------------------------------------

INSERT INTO gateway.model_usage_price(
    id, model_code, usage_dimension, resolution_tier,
    minimum_units, minimum_charge, unit_price,
    provider_unit_cost, retry_rate, target_margin_ratio, min_absolute_margin,
    price_version, effective_from, official_source)
VALUES
    (gen_random_uuid(), 'image-basic', 'IMAGE', '2K',
     1, 86, 86, 60, 0, 0.30000, 25, 'image-v1',
     '2026-09-28T00:00:00Z',
     'product:doubao-seedream-5.0-pro output 0.30-0.60 CNY/image, worst case')
ON CONFLICT (model_code, usage_dimension, resolution_tier, price_version) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8. Assertions
--
-- Asserted rather than assumed: a tier the gateway refuses, a cost basis the
-- platform cannot read, or a channel that looks callable without a credential
-- are all failures this migration must not leave behind.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    actual varchar(16);
    cost bigint;
    broken integer;
BEGIN
    SELECT tier.resolution_tier INTO actual
    FROM gateway.image_alias_tier tier
    WHERE tier.model_code = 'image-basic';

    IF actual IS NULL THEN
        RAISE EXCEPTION 'image_alias_tier_missing:image-basic';
    END IF;
    IF actual <> '2K' THEN
        RAISE EXCEPTION 'image_alias_tier_mismatch:expected=2K:actual=%', actual;
    END IF;

    -- The cost basis the platform mirror registers (platform V83:
    -- effective_unit_cost 60, retail_unit_price 86 for image-basic at 2K) must be
    -- exactly what GET /internal/prices/effective-unit-cost resolves, because that
    -- endpoint is what makes the two agree at publication time.
    SELECT cost_view.effective_unit_cost INTO cost
    FROM gateway.image_effective_cost cost_view
    WHERE cost_view.model_code = 'image-basic'
      AND cost_view.resolution_tier = '2K';

    IF cost IS NULL THEN
        RAISE EXCEPTION 'image_effective_cost_missing:image-basic';
    END IF;
    IF cost <> 60 THEN
        RAISE EXCEPTION 'image_effective_cost_mismatch:expected=60:actual=%', cost;
    END IF;

    -- Every IMAGE-capable alias must be priced and tier-registered, because
    -- ImageGenerationService refuses the ones that are not.
    SELECT count(*) INTO broken
    FROM gateway.model model
    WHERE model.capabilities @> '["IMAGE_GEN"]'::jsonb
      AND NOT EXISTS (
            SELECT 1 FROM gateway.image_alias_tier tier
            WHERE tier.model_code = model.code);

    IF broken > 0 THEN
        RAISE EXCEPTION 'image_capable_alias_without_a_registered_tier:count=%', broken;
    END IF;

    SELECT count(*) INTO broken
    FROM gateway.image_alias_tier tier
    WHERE NOT EXISTS (
        SELECT 1
        FROM gateway.image_effective_cost cost
        WHERE cost.model_code = tier.model_code
          AND cost.resolution_tier = tier.resolution_tier);

    IF broken > 0 THEN
        RAISE EXCEPTION 'image_alias_without_a_cost_basis:count=%', broken;
    END IF;

    -- No alias may hold a current rate-card row in BOTH generation dimensions.
    -- The two views are in different units (分/秒 vs 分/张) and
    -- /internal/prices/effective-unit-cost drops such an alias rather than
    -- picking one, so this shape would silently un-price a sellable alias.
    SELECT count(*) INTO broken
    FROM (
        SELECT model_code
        FROM (
            SELECT model_code FROM gateway.video_effective_cost
            UNION ALL
            SELECT model_code FROM gateway.image_effective_cost
        ) both_dimensions
        GROUP BY model_code
        HAVING count(*) > 1
    ) ambiguous;

    IF broken > 0 THEN
        RAISE EXCEPTION 'alias_priced_in_both_generation_dimensions:count=%', broken;
    END IF;

    -- The channel must be registered without a credential (V71 precedent), so a
    -- fresh install cannot appear to be able to call the vendor.
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'tianyi-image-primary'
      AND (channel.enabled
           OR channel.encrypted_api_key IS NOT NULL
           OR channel.last_health_status <> 'UNKNOWN');

    IF broken > 0 THEN
        RAISE EXCEPTION 'tianyi_image_channel_must_be_uncredentialed_and_disabled';
    END IF;

    -- IS DISTINCT FROM, not <>: a missing key yields NULL, and a NULL comparison
    -- would silently pass the check it exists to perform.
    SELECT count(*) INTO broken
    FROM gateway.channel channel
    WHERE channel.code = 'tianyi-image-primary'
      AND channel.settings -> 'credentialOwner'
          IS DISTINCT FROM '"system-gateway"'::jsonb;

    IF broken > 0 THEN
        RAISE EXCEPTION 'tianyi_image_channel_settings_missing_credential_owner';
    END IF;
END $$;
