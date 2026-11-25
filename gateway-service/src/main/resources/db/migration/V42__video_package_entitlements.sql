-- Video Package Entitlements
--
-- Carries a package's usage dimension from the platform catalogue into the
-- gateway, so a seconds-denominated package is enforced in seconds instead of
-- being silently treated as a token package.
--
-- V40 added usage_dimension / seconds_quota to
-- gateway.package_instance_model_quota but left token_quota NOT NULL, which
-- forced a seconds quota to be written into a token column. That is the exact
-- mislabelling the dimension column exists to prevent, so this migration
-- relaxes the constraint and makes each dimension use its own column.
--
-- Design: docs/superpowers/specs/2026-09-12-multimodal-pricing-and-video-package-design.md

-- ---------------------------------------------------------------------------
-- 1. Package-level dimension
--
-- total_token_quota stays NOT NULL and keeps its CHECK (> 0) because relaxing it
-- would break every existing reader. Instead it is dimension-carrying: for a
-- TOKEN package it is tokens, for a VIDEO_SECOND package it is seconds. The
-- same convention is applied on the platform side in V54.
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.package_instance
    ADD COLUMN usage_dimension varchar(20) NOT NULL DEFAULT 'TOKEN',
    ADD CONSTRAINT ck_package_instance_usage_dimension
        CHECK (usage_dimension IN ('TOKEN', 'VIDEO_SECOND', 'IMAGE'));

COMMENT ON COLUMN gateway.package_instance.usage_dimension IS
    'Unit of total_token_quota, reserved_tokens and used_tokens for this instance. '
    'TOKEN = tokens; VIDEO_SECOND = seconds. Admission, settlement and exhaustion '
    'all compare against total_token_quota in this unit.';
COMMENT ON COLUMN gateway.package_instance.total_token_quota IS
    'Package-wide quota in the unit named by usage_dimension.';
COMMENT ON COLUMN gateway.package_instance.reserved_tokens IS
    'Reserved units of usage_dimension (tokens OR seconds), not always tokens.';
COMMENT ON COLUMN gateway.package_instance.used_tokens IS
    'Consumed units of usage_dimension (tokens OR seconds), not always tokens.';

-- ---------------------------------------------------------------------------
-- 2. One counter column per dimension on the per-model quota
--
-- V40's CHECK already requires seconds_quota for a non-TOKEN row and forbids it
-- for a TOKEN row. token_quota must therefore become nullable, or a seconds row
-- has to carry a meaningless token number that a reader could mistake for a real
-- token quota.
-- ---------------------------------------------------------------------------

ALTER TABLE gateway.package_instance_model_quota
    ALTER COLUMN token_quota DROP NOT NULL;

-- V40's constraint assumed token_quota stayed non-null for every dimension.
ALTER TABLE gateway.package_instance_model_quota
    DROP CONSTRAINT ck_model_quota_values;

ALTER TABLE gateway.package_instance_model_quota
    ADD CONSTRAINT ck_model_quota_values CHECK (
        reserved_tokens >= 0 AND used_tokens >= 0
        AND (token_quota IS NULL OR token_quota > 0)
        AND (seconds_quota IS NULL OR seconds_quota > 0)
        AND ((usage_dimension = 'TOKEN'
                AND token_quota IS NOT NULL
                AND seconds_quota IS NULL
                AND reserved_tokens + used_tokens <= token_quota)
            OR (usage_dimension <> 'TOKEN'
                AND seconds_quota IS NOT NULL
                AND token_quota IS NULL
                AND reserved_tokens + used_tokens <= seconds_quota)));
