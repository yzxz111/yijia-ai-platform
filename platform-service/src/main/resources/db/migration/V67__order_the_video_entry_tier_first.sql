-- Put The 480P Entry Tier First In The Video Line
--
-- V66 inserted the two entry packages at display_order 45/46 -- after VIDEO_STARTER's
-- 40 -- which reads wrong on the customer page. The cheapest way in (CNY 59 for 100
-- seconds at 480P) appeared BELOW the CNY 129 mixed-resolution tier, so the video tab
-- opened on the dearer plan and the entry point looked like an afterthought.
--
-- 36/37 places the entry pair at the head of the video line: the token plans keep
-- 0-30, the video line starts at 36, and VIDEO_STARTER / VIDEO_CREATOR / VIDEO_PRO
-- keep 40 / 50 / 60. The console's own tier map mirrors these numbers
-- (web-console/src/views/user/PlansView.vue PLAN_TIER_ORDER), so a change here that
-- is not mirrored there would silently reorder the cards.
--
-- The annual package sits directly behind the monthly one on purpose: they are one
-- product sold two ways, and sorting them apart (which the console's price fallback
-- did before it learned these codes) buries the pair between unrelated tiers.
--
-- package_plan rows are mutable -- only package_plan_version rows are immutable by
-- trigger -- so this is an ordinary UPDATE rather than a new publication.

UPDATE platform.package_plan
SET display_order = CASE code
        WHEN 'VIDEO_LITE'        THEN 36
        WHEN 'VIDEO_LITE_ANNUAL' THEN 37
        ELSE display_order
    END
WHERE code IN ('VIDEO_LITE', 'VIDEO_LITE_ANNUAL');

-- Asserted rather than assumed: the entry pair leads the video line, the three
-- pre-existing video tiers did not move, and the seeded video tiers have distinct
-- positions. Administrators may legitimately reuse a display order on other plans.
DO $$
DECLARE
    entry_order   integer;
    annual_order  integer;
    starter_order integer;
    duplicates    integer;
BEGIN
    SELECT display_order INTO entry_order
    FROM platform.package_plan WHERE code = 'VIDEO_LITE';
    SELECT display_order INTO annual_order
    FROM platform.package_plan WHERE code = 'VIDEO_LITE_ANNUAL';
    SELECT display_order INTO starter_order
    FROM platform.package_plan WHERE code = 'VIDEO_STARTER';

    IF entry_order IS NULL OR annual_order IS NULL OR starter_order IS NULL THEN
        RAISE EXCEPTION 'video_plan_missing_from_the_catalog';
    END IF;
    IF NOT (entry_order < annual_order AND annual_order < starter_order) THEN
        RAISE EXCEPTION 'video_entry_tier_not_first:lite=%:annual=%:starter=%',
            entry_order, annual_order, starter_order;
    END IF;
    IF entry_order <= 30 THEN
        RAISE EXCEPTION 'video_entry_tier_collides_with_the_token_line:order=%', entry_order;
    END IF;

    SELECT count(*) INTO duplicates
    FROM (
        SELECT display_order FROM platform.package_plan
        WHERE code IN ('VIDEO_LITE', 'VIDEO_LITE_ANNUAL',
                       'VIDEO_STARTER', 'VIDEO_CREATOR', 'VIDEO_PRO')
        GROUP BY display_order HAVING count(*) > 1
    ) AS repeated;

    IF duplicates > 0 THEN
        RAISE EXCEPTION 'catalog_display_order_not_unique:count=%', duplicates;
    END IF;
END $$;
