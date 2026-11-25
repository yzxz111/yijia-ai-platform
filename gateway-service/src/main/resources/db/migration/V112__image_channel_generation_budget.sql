-- ---------------------------------------------------------------------------
-- The image channel's call budget must not be shorter than a real generation.
--
-- V81 registered tianyi-image-primary with timeout_ms = 60000 (the V1 column
-- default). For image generation that number is simply wrong, because the
-- protocol is SYNCHRONOUS: the duration of the HTTP call IS the duration of the
-- generation. Measured on this exact channel — 37.3s text-to-image, and
-- 74.0 / 80.0 / 80.6 / 83.4 / 120.7 s image-to-image (see
-- docs/生图与生视频接口说明.md §1.4.5) — five of six generations exceeded
-- 60 seconds, so a 60-second budget cuts off calls that were about to succeed.
--
-- The cost of that cut is not a retry. The gateway cannot know whether the
-- vendor already started billing, so the task row stays SUBMITTED and its
-- reservation stays held; only the 5-minute recovery sweep
-- (ImageGenerationService.TASK_TTL) ends it as status FAILED,
-- error_category gateway_task_expired, and refunds the quota. The customer's
-- client then replays the same Idempotency-Key — the retry the client contract
-- mandates for an unresolved outcome — and receives
-- 409 image_generation_previously_failed for that key, permanently, while the
-- platform may already have paid the vendor for the image it discarded.
--
-- TianYiYunImageGenerationAdapter.CALL_TIMEOUT_CAP is 150s and the effective
-- budget is min(channel.timeout_ms, cap). Raising the channel row to 180000
-- therefore lets the documented 150-second ceiling govern, instead of a value
-- that was silently overriding it downwards. The cap still protects against an
-- operator configuring something absurd (the adapter comment's "configured to
-- an hour" case).
--
-- Only a too-small budget is corrected: a deliberately larger operator value
-- is left alone.
-- ---------------------------------------------------------------------------

UPDATE gateway.channel
SET timeout_ms = 180000,
    updated_at = now()
WHERE code = 'tianyi-image-primary'
  AND timeout_ms < 150000;
