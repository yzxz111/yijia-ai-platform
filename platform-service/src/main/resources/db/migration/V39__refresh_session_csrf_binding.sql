ALTER TABLE platform.refresh_token
    ADD COLUMN csrf_token_hash char(64);

UPDATE platform.refresh_token
SET csrf_token_hash = repeat('0', 64),
    revoked_at = COALESCE(revoked_at, now()),
    revocation_reason = COALESCE(revocation_reason, 'CSRF_BINDING_REQUIRED')
WHERE csrf_token_hash IS NULL;

ALTER TABLE platform.refresh_token
    ALTER COLUMN csrf_token_hash SET NOT NULL,
    ADD CONSTRAINT ck_refresh_token_csrf_hash
        CHECK (csrf_token_hash ~ '^[0-9a-f]{64}$');
