-- Raise The Enforced Floors To The Product's 30% Guarantee
--
-- The product's requirement is that no package is sold below a 30% gross margin.
-- The enforced floors did not say that: the TOKEN dimension was already 0.30, but
-- VIDEO_SECOND and IMAGE were 0.25, so a video package could be published at a
-- margin the product would not accept. The warning band moves with the floor so an
-- operator is told before publication rather than after.
--
--   dimension      minimum before -> now    warning before -> now
--   TOKEN                0.30 -> 0.30             0.35 -> 0.35
--   VIDEO_SECOND         0.25 -> 0.30             0.30 -> 0.35
--   IMAGE                0.25 -> 0.30             0.30 -> 0.35
--
-- This table became the guard's real source of truth in V57, so this migration
-- changes what publication actually enforces. Every currently PUBLISHED version was
-- checked against the raised floor before writing this, against the dearest
-- ROUTABLE provider per alias (not just the enabled ones), and all of them clear it:
--
--   ENTERPRISE 45.5%   ADVANCED 58.3%   STARTER 46.8%
--   VIDEO_PRO 39.2%    VIDEO_CREATOR 45.2%   VIDEO_STARTER 46.5%
--   FREE is price 0 — a giveaway by design, not a margin failure.
--
-- So no published version is invalidated by this, and no existing customer's price
-- changes. What it does change is the gate for the NEXT version: a new token or
-- video version must clear 30%, not 25%.
--
-- The 40% ceiling in the product's "30-40%" band is deliberately NOT enforced. 40% is
-- a target an operator aims at, and a hard ceiling would forbid a legitimately
-- higher-margin package (a small quota, a cheap alias) for no benefit. The floor is
-- the part that protects against loss, so the floor is the part that is enforced.

UPDATE platform.package_dimension_price_policy
SET minimum_margin = 0.30000,
    warning_margin = 0.35000
WHERE usage_dimension IN ('VIDEO_SECOND', 'IMAGE')
  AND minimum_margin < 0.30000;

-- TOKEN already sits at 0.30/0.35; asserted rather than assumed, so a future edit that
-- weakens it fails here.
DO $$
DECLARE
    token_floor numeric;
    token_warn  numeric;
BEGIN
    SELECT policy.minimum_margin, policy.warning_margin
    INTO token_floor, token_warn
    FROM platform.package_dimension_price_policy policy
    WHERE policy.usage_dimension = 'TOKEN';

    IF token_floor IS NULL OR token_floor < 0.30000 THEN
        RAISE EXCEPTION 'token_margin_floor_below_product_minimum:%', token_floor;
    END IF;
    IF token_warn IS NULL OR token_warn < token_floor THEN
        RAISE EXCEPTION 'token_warning_floor_below_minimum:%:%', token_warn, token_floor;
    END IF;

    -- No dimension may sit below the product minimum after this migration.
    PERFORM 1 FROM platform.package_dimension_price_policy
    WHERE minimum_margin < 0.30000
       OR warning_margin < minimum_margin;
    IF FOUND THEN
        RAISE EXCEPTION 'a_dimension_floor_is_below_the_product_minimum';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- Assert every PUBLISHED version already clears the raised floor
--
-- The check that makes the guarantee above real rather than asserted. It uses the
-- dearest routable provider per alias and honours each per-model ceiling, which is
-- how PackageMarginPolicy fills a package.
--
-- The cost basis is embedded for the same reasons V56 embeds it: a migration is a
-- fixed record of what it was verified against, and platform Flyway owns only the
-- `platform` schema, so a cross-schema read would fail in a platform-only container.
-- gateway.provider_model_price at the time of writing, 分 per million OUTPUT tokens,
-- taking the MAXIMUM across every provider row regardless of enabled state:
--   text-basic 28   text-standard 202   text-pro 627   text-max 750
--   vision-basic 60  vision-pro 160      file-basic 200
--   multimodal-basic 200 — COMPOSITE, no price row of its own; priced at the ceiling of
--   its components (file-basic 200 dominates), which is what the gateway's composite
--   price resolution returns.
-- video aliases: video-max 345  video-standard 115  video-pro 92  video-basic 69  video-budget 35
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    plan record;
    worst numeric;
BEGIN
    FOR plan IN
        SELECT version.id, plan_row.code, version.price_amount, version.total_token_quota,
               version.usage_dimension
        FROM platform.package_plan_version version
        JOIN platform.package_plan plan_row ON plan_row.id = version.package_plan_id
        WHERE version.status = 'PUBLISHED' AND version.price_amount > 0
    LOOP
        IF plan.usage_dimension = 'TOKEN' THEN
            -- The guard's own fill: dearest alias first, each consuming min(cap, what the
            -- earlier ones left), so a NULL cap means "this alias may take the
            -- remainder". The window's ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING is
            -- what reserves the earlier consumption.
            WITH basis(model_code, unit_cost) AS (
                VALUES ('text-basic', 28), ('text-standard', 202), ('text-pro', 627),
                       ('text-max', 750), ('vision-basic', 60), ('vision-pro', 160),
                       ('file-basic', 200), ('multimodal-basic', 200)
            ), perms AS (
                SELECT model.model_token_quota AS cap, basis.unit_cost
                FROM platform.package_plan_version_model model
                JOIN basis ON basis.model_code = model.model_code
                WHERE model.package_plan_version_id = plan.id
            ), filled AS (
                SELECT cap, unit_cost,
                       least(coalesce(cap, plan.total_token_quota),
                             greatest(plan.total_token_quota - coalesce(
                                 sum(coalesce(cap, plan.total_token_quota)) OVER (
                                     ORDER BY unit_cost DESC, cap
                                     ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
                FROM perms
            )
            SELECT coalesce(sum(consumed * unit_cost), 0) / 1000000.0 INTO worst FROM filled;
        ELSE
            WITH basis(model_code, unit_cost) AS (
                VALUES ('video-max', 345), ('video-standard', 115), ('video-pro', 92),
                       ('video-basic', 69), ('video-budget', 35)
            ), perms AS (
                SELECT model.model_token_quota AS cap, basis.unit_cost
                FROM platform.package_plan_version_model model
                JOIN basis ON basis.model_code = model.model_code
                WHERE model.package_plan_version_id = plan.id
            ), filled AS (
                SELECT cap, unit_cost,
                       least(coalesce(cap, plan.total_token_quota),
                             greatest(plan.total_token_quota - coalesce(
                                 sum(coalesce(cap, plan.total_token_quota)) OVER (
                                     ORDER BY unit_cost DESC, cap
                                     ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0), 0)) AS consumed
                FROM perms
            )
            SELECT coalesce(sum(consumed * unit_cost), 0) INTO worst FROM filled;
        END IF;

        IF worst > 0
                AND (plan.price_amount - worst) / plan.price_amount < 0.30000 THEN
            RAISE EXCEPTION 'published_plan_below_thirty_percent:%:dimension=:%:price=%:worst=%:margin=%',
                plan.code, plan.usage_dimension, plan.price_amount, round(worst),
                round((plan.price_amount - worst) / plan.price_amount, 5);
        END IF;
    END LOOP;
END $$;
