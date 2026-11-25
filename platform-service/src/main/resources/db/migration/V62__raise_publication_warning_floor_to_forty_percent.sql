-- Raise The Publication Warning Floor To 40%
--
-- The product target is a 30-40% worst-case margin. Only the bottom of that band
-- was enforced: minimum_margin is 0.30 for every dimension (V58 raised
-- VIDEO_SECOND and IMAGE to it) and it stays what publication REFUSES below. The
-- warning floor, which drives the stored margin_warning flag an operator sees, sat
-- at 0.35 -- below the top of the band -- so a plan that published at 31% carried
-- no flag at all and nothing told anyone it was one cost change away from the
-- floor.
--
--   dimension      minimum        warning before -> now
--   TOKEN          0.30000              0.35000 -> 0.40000
--   VIDEO_SECOND   0.30000              0.35000 -> 0.40000
--   IMAGE          0.30000              0.35000 -> 0.40000
--
-- CONSEQUENCE, ACCEPTED DELIBERATELY: a version whose worst-case margin lands
-- inside 30-40% (>= floor, < 0.40) now publishes with margin_warning = true, where
-- before it published silently. That is the intent -- an early warning before the
-- version breaches the floor -- not a regression. Of the versions published before
-- this migration, STARTER 37.9%, ADVANCED 35.3%, ENTERPRISE 34.8% and VIDEO_PRO
-- 39.2% are inside the band and would carry the flag under the new floor;
-- VIDEO_STARTER 46.5% and VIDEO_CREATOR 45.2% are above it and would not.
--
-- The flag is a PUBLICATION-TIME SNAPSHOT, so the already-published rows keep the
-- value they were approved with: published versions are immutable
-- (platform.reject_published_version_mutation) and rewriting one here would break
-- the guarantee that a customer's version never changes under them. V63 republishes
-- every tier that is inside the band with the flag recomputed against this floor,
-- which is where the four rows above pick the new value up.
--
-- The floor is NOT raised. A 40% hard ceiling is still deliberately absent: 40% is a
-- target an operator aims at, and forbidding a legitimately higher-margin package
-- (VIDEO_STARTER and VIDEO_CREATOR both sit above 45%) buys nothing. The floor is
-- the part that protects against loss.

UPDATE platform.package_dimension_price_policy
SET warning_margin = 0.40000
WHERE usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE');

-- Asserted rather than assumed: the three dimensions the product sells all carry
-- the new warning floor, none of them was weakened below the product's 30%
-- guarantee, and the table's own ordering invariant still holds.
DO $$
DECLARE
    dimension_count integer;
    broken          integer;
BEGIN
    SELECT count(*) INTO dimension_count
    FROM platform.package_dimension_price_policy
    WHERE usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE')
      AND minimum_margin = 0.30000
      AND warning_margin = 0.40000;

    IF dimension_count <> 3 THEN
        RAISE EXCEPTION 'publication_warning_floor_not_applied:applied=%', dimension_count;
    END IF;

    SELECT count(*) INTO broken
    FROM platform.package_dimension_price_policy
    WHERE minimum_margin < 0.30000
       OR warning_margin < minimum_margin;

    IF broken > 0 THEN
        RAISE EXCEPTION 'dimension_policy_weakens_the_thirty_percent_guarantee:count=%', broken;
    END IF;

    -- A hard ceiling must not have crept in: every dimension still permits a plan
    -- better than the 40% target.
    PERFORM 1 FROM platform.package_dimension_price_policy WHERE minimum_margin > 0.30000;
    IF FOUND THEN
        RAISE EXCEPTION 'dimension_floor_raised_above_the_product_minimum';
    END IF;
END $$;
