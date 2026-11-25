ALTER TABLE gateway.package_instance
    ADD COLUMN identity_key varchar(128),
    ADD COLUMN daily_request_limit integer,
    ADD COLUMN max_output_tokens integer,
    ADD COLUMN max_file_size_bytes bigint NOT NULL DEFAULT 20971520,
    ADD COLUMN once_per_user boolean NOT NULL DEFAULT false;

UPDATE gateway.package_instance
SET identity_key = user_id::text
WHERE identity_key IS NULL;

ALTER TABLE gateway.package_instance
    ALTER COLUMN identity_key SET NOT NULL,
    ADD CONSTRAINT ck_package_instance_optional_limits CHECK (
        (daily_request_limit IS NULL OR daily_request_limit > 0)
        AND (max_output_tokens IS NULL OR max_output_tokens > 0)
        AND max_file_size_bytes > 0
    );

CREATE UNIQUE INDEX uq_free_package_per_identity
    ON gateway.package_instance (identity_key)
    WHERE package_code = 'FREE';
