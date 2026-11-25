-- Lower The Video Warning Floor To 35%
--
-- The video dimension keeps its 30% hard floor (publication still REFUSES below
-- it) and its warning margin moves from 40% to 35%. Nothing else changes: the
-- token and image dimensions stay at 40%, because this decision was taken for the
-- video product line only.
--
-- WHY IT MOVED. The video ladder is being extended with a cheap 480P entry tier
-- (see V66) whose economics are honest but thin: a 480P second costs 35 分 and the
-- tier sells at 59 分 (monthly) / 54.45 分 (annual). The annual package lands at a
-- 35.7% worst-case margin -- comfortably above the 30% floor the database and the
-- publication guard enforce, and just under the old 40% warning line. Flagging a
-- deliberately-priced, above-floor package as a warning teaches operators to
-- ignore the flag, which costs more than the flag is worth.
--
-- WHAT IS *NOT* HAPPENING: the floor is not lowered (V58 raised VIDEO_SECOND and
-- IMAGE to 0.30 and that stands), and no published version is rewritten --
-- margin_warning is a publication-time snapshot and published versions are
-- immutable by trigger. Existing versions keep the flag they were approved with;
-- only versions published from here on are measured against 35%.
--
--   dimension      minimum        warning before -> now
--   VIDEO_SECOND   0.30000              0.40000 -> 0.35000
--   TOKEN          0.30000              0.40000 -> 0.40000  (unchanged)
--   IMAGE          0.30000              0.40000 -> 0.40000  (unchanged)

UPDATE platform.package_dimension_price_policy
SET warning_margin = 0.35000
WHERE usage_dimension = 'VIDEO_SECOND';

-- Asserted rather than assumed: the video warning line is exactly 35%, the floor
-- it must stay above is untouched, the other two dimensions were not dragged
-- along, and the table's own ordering invariant still holds everywhere.
DO $$
DECLARE
    video_rows   integer;
    other_rows   integer;
    broken       integer;
BEGIN
    SELECT count(*) INTO video_rows
    FROM platform.package_dimension_price_policy
    WHERE usage_dimension = 'VIDEO_SECOND'
      AND minimum_margin = 0.30000
      AND warning_margin = 0.35000;

    IF video_rows <> 1 THEN
        RAISE EXCEPTION 'video_warning_floor_not_applied:rows=%', video_rows;
    END IF;

    SELECT count(*) INTO other_rows
    FROM platform.package_dimension_price_policy
    WHERE usage_dimension IN ('TOKEN', 'IMAGE')
      AND minimum_margin = 0.30000
      AND warning_margin = 0.40000;

    IF other_rows <> 2 THEN
        RAISE EXCEPTION 'token_or_image_warning_floor_moved:rows=%', other_rows;
    END IF;

    SELECT count(*) INTO broken
    FROM platform.package_dimension_price_policy
    WHERE minimum_margin < 0.30000
       OR warning_margin < minimum_margin;

    IF broken > 0 THEN
        RAISE EXCEPTION 'dimension_policy_weakens_the_thirty_percent_guarantee:count=%', broken;
    END IF;
END $$;
