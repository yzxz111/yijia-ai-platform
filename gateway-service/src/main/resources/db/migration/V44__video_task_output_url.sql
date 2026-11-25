-- Video Task Output URL
--
-- A finished generation has to be retrievable. V41 stored only the task's state,
-- so the location the vendor returns with its terminal poll was discarded and a
-- succeeded task offered no way to fetch the video it had just been billed for.
--
-- The location is stored, not the bytes: mirroring generations into object
-- storage is a separate concern (the platform already has an OSS upload path)
-- and would put a per-second product's egress on the gateway. The gateway serves
-- its own authenticated content endpoint and resolves the stored location from
-- there, so the vendor's URL never becomes part of the client contract and a
-- vendor URL that is bound to the caller's credentials can later be proxied
-- without changing that contract.
--
-- Only a SUCCEEDED task may carry a location. A vendor that returns one for a
-- failed generation (or a retry that overwrote a state) must not leave a row that
-- claims a retrievable video, because the content endpoint trusts this column.
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md

ALTER TABLE gateway.video_generation_task
    ADD COLUMN output_url text;

ALTER TABLE gateway.video_generation_task
    ADD CONSTRAINT ck_video_task_output_url
        CHECK (output_url IS NULL OR status = 'SUCCEEDED');

COMMENT ON COLUMN gateway.video_generation_task.output_url IS
    'Vendor-issued location of the finished generation, written at the terminal '
    'transition. Never returned to a client verbatim: GET /v1/videos/{request_id}'
    '/content resolves it after an ownership check. NULL unless status = SUCCEEDED.';
