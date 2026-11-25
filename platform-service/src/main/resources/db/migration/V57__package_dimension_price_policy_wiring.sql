-- package_dimension_price_policy: The Comment Now Describes What Happens
--
-- V54's COMMENT ON TABLE claimed this table "Replaces the hard-coded
-- TOKENS_PER_MILLION / MINIMUM_MARGIN pair in PackageMarginPolicy, which could only ever
-- price token packages." For two releases that was false: no Java read the table, and the
-- floors actually enforced were the constants in PackageMarginPolicy (0.30000 / 0.35000
-- for tokens, 0.25000 / 0.30000 for seconds, divisors 1,000,000 and 1). The table and the
-- constants agreed for all three seeded rows, which is exactly why an operator editing the
-- table changed no outcome and nobody noticed.
--
-- The wiring now exists, so the claim is true and this comment says how:
--
--   * PackageVersionService.dimensionPolicy reads this table — through
--     DimensionPricePolicyRepository, uncached — on every version create and every
--     publish, so an edit here takes effect on the next publication.
--   * PackageMarginPolicy.fromPolicyRow turns the row into the policy the guard prices
--     with: quota_units_per_cost_unit, minimum_margin, warning_margin and unit_label all
--     come from the row.
--   * A usage dimension with no row keeps the fallback documented in
--     PackageMarginPolicy.fallbackPolicy, which is the pre-table behaviour above.
--   * A row that is present but unusable (quota_units_per_cost_unit <= 0, a margin
--     outside [0, 1), or warning_margin < minimum_margin) fails the publication with
--     invalid_package_dimension_price_policy:<DIMENSION>:<column> instead of falling back.
--     A mistyped floor must never yield a weaker guard than the operator configured.
--
-- Why the V54 file itself is not edited: it has been applied (platform.flyway_schema_history
-- version 54, checksum 912013425) and Flyway validates the checksum of applied migrations
-- on every startup, so changing any byte of it — including this comment — fails the next
-- start with a checksum mismatch. See docs/数据库设计总览.md ("绝不修改已发布的迁移").
-- A COMMENT issued here supersedes the V54 text in every database, including the ones that
-- already ran V54, which editing the old file could not do.
--
-- Evidence: docs/数据库-platform-schema.md section 3.2.4.

COMMENT ON TABLE platform.package_dimension_price_policy IS
    'Per-dimension scaling and margin floors for package publication, read by '
    'PackageVersionService through DimensionPricePolicyRepository on every version create '
    'and publish. Overrides the fallback constants in PackageMarginPolicy for every '
    'dimension that has a row. Absence of a row means "use the documented fallback"; a row '
    'that is present but invalid fails publication (fail closed) instead of falling back.';

COMMENT ON COLUMN platform.package_dimension_price_policy.minimum_margin IS
    'Hard floor: a version whose worst-case margin is below this must not publish. Read on '
    'every create and publish. The guard itself re-checks 0 <= minimum_margin < 1 and '
    'refuses to price a row outside it.';

COMMENT ON COLUMN platform.package_dimension_price_policy.warning_margin IS
    'Floor below which a version publishes with margin_warning = true. Must be >= '
    'minimum_margin; a row where it is not fails publication with '
    'invalid_package_dimension_price_policy instead of falling back to a weaker floor.';

COMMENT ON COLUMN platform.package_dimension_price_policy.quota_units_per_cost_unit IS
    'How many quota units make up one cost unit when multiplying a unit cost: 1000000 for '
    'a 分-per-million-token price against a token quota, 1 for a 分-per-second price '
    'against a second quota. Read from this row; no longer hard-coded in PackageMarginPolicy.';

COMMENT ON COLUMN platform.package_dimension_price_policy.unit_label IS
    'Unit this dimension''s quota is expressed in, carried into the pricing policy so the '
    'guard can name it. It does not change the arithmetic.';
