-- Register The Resolution Each Video Alias Sells
--
-- WHY THIS TABLE EXISTS. The catalogue treats a video alias as selling exactly one
-- resolution tier: platform.model_usage_dimension is keyed by model_code with a
-- single resolution_tier (video-budget 480P, video-basic 720P, video-standard
-- 1080P, video-pro 720P, video-max 1080P), and a package version's worst-case
-- margin is computed against that tier's per-second cost. The gateway did not
-- enforce the mapping. VideoGenerationService admitted any of 480P/720P/1080P for
-- any alias, because the only table that ever carried it -- V40's
-- gateway.model_usage_price -- was deprecated in V51 and is read by nothing.
--
-- WHAT THAT COSTS. video-budget costs 35 分/second as a 480P alias and 115
-- 分/second if the same alias is driven at 1080P. The 480P entry tier published by
-- platform V66 sells at 59 分/second with a 40.7% worst-case margin, which is a
-- loss the moment a customer asks that "480P" package for a 1080P render. A tier
-- priced for the resolution it sells only holds if the resolution is enforced.
--
-- FAIL CLOSED, DELIBERATELY. The guard refuses a resolution the alias does not sell
-- AND refuses an alias with no row in this table. The second half matters: a video
-- alias added later without a tier row cannot be priced by the platform either, so
-- admitting it would sell seconds the catalogue has no cost basis for. Registering
-- the tier is one INSERT, and the assertion at the bottom of this migration proves
-- every video alias in the catalogue has one.
--
-- SCOPE. This is the gateway's own copy of the mapping rather than a cross-schema
-- read of platform.model_usage_dimension: platform Flyway owns only the `platform`
-- schema, the gateway must answer this before it talks to a vendor, and a
-- cross-schema join would make a platform-only migration test fail.

CREATE TABLE gateway.video_alias_tier (
    model_code varchar(128) PRIMARY KEY
        REFERENCES gateway.model(code) ON DELETE CASCADE,
    resolution_tier varchar(16) NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_video_alias_tier_value
        CHECK (resolution_tier IN ('480P', '720P', '1080P'))
);

COMMENT ON TABLE gateway.video_alias_tier IS
    'The one resolution tier each video alias sells. Enforced by '
    'VideoGenerationService: a request whose resolution is not this value is refused '
    'with video_resolution_not_sold_by_model, and an alias with no row here is '
    'refused with video_model_resolution_unregistered. Mirrors '
    'platform.model_usage_dimension.resolution_tier, which is the basis the package '
    'catalogue prices a video version against.';

COMMENT ON COLUMN gateway.video_alias_tier.resolution_tier IS
    '480P / 720P / 1080P. The per-second cost of an alias is quoted per resolution '
    '(video-budget 480P is 35 分/second, the same alias at 1080P would be 115), so '
    'this column is what makes a "480P package" priceable.';

INSERT INTO gateway.video_alias_tier(model_code, resolution_tier)
SELECT model.code, tier.resolution_tier
FROM (VALUES
        ('video-budget',   '480P'),
        ('video-basic',    '720P'),
        ('video-standard', '1080P'),
        ('video-pro',      '720P'),
        ('video-max',      '1080P')
     ) AS tier(model_code, resolution_tier)
JOIN gateway.model model ON model.code = tier.model_code
ON CONFLICT (model_code) DO UPDATE SET
    resolution_tier = EXCLUDED.resolution_tier;

-- Asserted rather than assumed: the five seeded aliases carry the exact tiers the
-- catalogue prices them at, and no VIDEO-capable alias is left unregistered (which
-- the fail-closed guard would turn into a hard 422 at delivery time).
DO $$
DECLARE
    expected  record;
    actual    varchar(16);
    missing   integer;
BEGIN
    FOR expected IN
        SELECT * FROM (VALUES
            ('video-budget',   '480P'),
            ('video-basic',    '720P'),
            ('video-standard', '1080P'),
            ('video-pro',      '720P'),
            ('video-max',      '1080P')
        ) AS wanted(model_code, resolution_tier)
    LOOP
        SELECT tier.resolution_tier INTO actual
        FROM gateway.video_alias_tier tier
        WHERE tier.model_code = expected.model_code;

        IF actual IS NULL THEN
            RAISE EXCEPTION 'video_alias_tier_missing:%', expected.model_code;
        END IF;
        IF actual <> expected.resolution_tier THEN
            RAISE EXCEPTION 'video_alias_tier_mismatch:%:expected=%:actual=%',
                expected.model_code, expected.resolution_tier, actual;
        END IF;
    END LOOP;

    SELECT count(*) INTO missing
    FROM gateway.model model
    WHERE model.capabilities @> '["VIDEO"]'::jsonb
      AND NOT EXISTS (
            SELECT 1 FROM gateway.video_alias_tier tier
            WHERE tier.model_code = model.code);

    IF missing > 0 THEN
        RAISE EXCEPTION 'video_capable_alias_without_a_registered_tier:count=%', missing;
    END IF;
END $$;
