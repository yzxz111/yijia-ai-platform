-- ---------------------------------------------------------------------------
-- Multi-reference image generation needs more time than a single image.
--
-- V112 raised the image channel budget from the V1 column default (60000) to
-- 180000, because single-image generation is synchronous and measured at
-- 37.3~120.7 s — a 60-second budget cut off generations that were about to
-- succeed.
--
-- Multi-reference generation (image as an array, 1~8 inputs, shipped
-- 2026-10-12) is slower still: direct upstream measurements on the live
-- channel were 104 s (2 inputs), 172 s (3), 140 s (4), 150.8 s (6) and
-- 136.7 s (8). The adapter cap was therefore raised to 240 s
-- (TianYiYunImageGenerationAdapter.CALL_TIMEOUT_CAP), and the channel budget
-- must not sit below that cap, otherwise the effective budget
-- min(channel.timeout_ms, cap) would once again cut off slow generations:
-- the task stays SUBMITTED, the quota is frozen, the 8-minute recovery sweep
-- (ImageGenerationService.TASK_TTL) refunds it, and the customer's idempotency
-- key is permanently poisoned with 409 image_generation_previously_failed
-- while the platform may already have paid the vendor for the discarded image.
--
-- Only a too-small budget is corrected: a deliberately larger operator value
-- is left alone.
-- ---------------------------------------------------------------------------

UPDATE gateway.channel
SET timeout_ms = 240000,
    updated_at = now()
WHERE code = 'tianyi-image-primary'
  AND timeout_ms < 240000;
