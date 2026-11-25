ALTER TABLE gateway.api_key
    ADD COLUMN managed boolean NOT NULL DEFAULT false,
    ADD COLUMN secret_ciphertext bytea,
    ADD COLUMN secret_key_version smallint,
    ADD COLUMN last_revealed_at timestamptz,
    ADD CONSTRAINT ck_api_key_managed_secret CHECK (
        (managed
            AND package_instance_id IS NOT NULL
            AND secret_ciphertext IS NOT NULL
            AND octet_length(secret_ciphertext) >= 29
            AND secret_key_version IS NOT NULL
            AND secret_key_version > 0)
        OR
        (NOT managed
            AND secret_ciphertext IS NULL
            AND secret_key_version IS NULL
            AND last_revealed_at IS NULL)
    );

CREATE UNIQUE INDEX uq_api_key_active_managed_package
    ON gateway.api_key(package_instance_id)
    WHERE managed AND status = 'ACTIVE';
