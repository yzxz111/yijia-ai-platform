-- Video Effective Cost: The Retry Allowance Is Already In The Stored Cost
--
-- V41 exposed gateway.video_effective_cost.effective_unit_cost as
-- ceil(provider_unit_cost * (1 + retry_rate)). That double-counts the retry
-- allowance, because V40 already stores provider_unit_cost as the EFFECTIVE
-- per-second cost: 720P wholesale 60 分 is stored as 69 分, 1080P 100 as 115,
-- Kling v3-turbo 80 as 92, Kling v3-omni 300 as 345. V40 says so in its own
-- comment, and ck_model_usage_price_margin validates every retail price against
-- exactly that stored number.
--
-- Re-applying the rate made every video alias look 15% more expensive than it
-- is. The visible consequence is not cosmetic: the platform's publication guard
-- is handed this figure as its cost basis, so a package priced to the published
-- rate card was costed at 80 分/s instead of 69 and assessed a 20% margin, below
-- the 25% floor for the seconds dimension — a video version priced exactly as
-- designed could not be published. The same number is what operators see as
-- estimatedCostAmount / estimatedMarginRatio, so both were overstated by 15%
-- across every video package.
--
-- The view now passes the stored cost through. retry_rate stays on the rate card
-- and in the view as the recorded allowance behind that cost — it is reporting
-- information, not a second multiplier.
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md
-- (rate card, "×1.15 retry" column: 0.60 -> 0.69 -> 1.00 retail = 31% margin).

CREATE OR REPLACE VIEW gateway.video_effective_cost AS
SELECT price.model_code,
       price.resolution_tier,
       price.provider_unit_cost,
       price.retry_rate,
       price.min_absolute_margin,
       price.target_margin_ratio,
       price.unit_price,
       price.price_version,
       -- Already retry-inclusive: see the header. Applying retry_rate here again
       -- would charge the failure allowance twice.
       price.provider_unit_cost AS effective_unit_cost
FROM gateway.model_usage_price price
WHERE price.usage_dimension = 'VIDEO_SECOND'
  AND price.effective_to IS NULL;

COMMENT ON VIEW gateway.video_effective_cost IS
    'Current per-second provider cost for video aliases, in 分, as stored on the '
    'rate card. provider_unit_cost already includes the retry allowance, so it is '
    'reported unchanged as effective_unit_cost. Consumed by the package '
    'publication margin guard, which scales it by SECONDS rather than by tokens.';

COMMENT ON COLUMN gateway.video_effective_cost.effective_unit_cost IS
    'Cost of one sold second including the failure allowance. Equal to '
    'provider_unit_cost; retry_rate is the recorded allowance behind it, not an '
    'additional multiplier.';
