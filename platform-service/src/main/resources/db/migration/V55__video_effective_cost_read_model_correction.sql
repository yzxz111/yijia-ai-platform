-- Video Effective Cost Read Model: Retry Is Already In The Stored Cost
--
-- V54 seeded platform.model_usage_dimension.effective_unit_cost by applying the
-- retry allowance a second time — video-basic 69 分/s became 80, video-standard
-- 115 became 133, video-max 345 became 397 — because the gateway view it was
-- copied from did the same. Both were wrong: gateway.model_usage_price.
-- provider_unit_cost is stored as the EFFECTIVE per-second cost
-- (720P wholesale 60 分 -> 69 分, exactly the "×1.15 retry" column of the design's
-- rate card), which is also the basis the rate card's own margin constraint
-- validates every retail price against. Gateway V45 removed the second
-- multiplication; this migration brings the platform's read model back in line.
--
-- What this changes in practice: nothing reads the two price columns today — the
-- repository selects only model_code and usage_dimension, and the publication
-- guard calls the gateway's /internal/prices/effective-unit-cost live. The
-- columns are still corrected because a read model that contradicts the table it
-- mirrors is a trap for the next reader, and because a future sync job would
-- otherwise have to reconcile two different definitions of "effective".
--
-- The three video package versions seeded by V54 keep the cost snapshot they
-- published with (4800 / 15200 / 48000 分 at 80 分/s). Published versions are
-- immutable by trigger, and the snapshot errs on the conservative side — it
-- reports less margin than the package actually earns. New publications price
-- from the corrected basis.
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md

UPDATE platform.model_usage_dimension
SET effective_unit_cost = CASE model_code
        WHEN 'video-budget'   THEN 35
        WHEN 'video-basic'    THEN 69
        WHEN 'video-standard' THEN 115
        WHEN 'video-pro'      THEN 92
        WHEN 'video-max'      THEN 345
        ELSE effective_unit_cost
    END,
    synced_at = now()
WHERE usage_dimension = 'VIDEO_SECOND'
  AND model_code IN ('video-budget', 'video-basic', 'video-standard', 'video-pro', 'video-max');

COMMENT ON COLUMN platform.model_usage_dimension.effective_unit_cost IS
    'Effective per-second provider cost in 分, mirroring '
    'gateway.model_usage_price.provider_unit_cost unchanged: that column already '
    'includes the retry allowance, so the retry rate must not be applied again.';

COMMENT ON COLUMN platform.model_usage_dimension.retail_unit_price IS
    'Retail price per second in 分, mirroring gateway.model_usage_price.unit_price.';
