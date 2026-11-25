ALTER TABLE gateway.package_instance
    ADD COLUMN package_kind varchar(20) NOT NULL DEFAULT 'STANDARD',
    ADD CONSTRAINT ck_package_instance_kind CHECK (package_kind IN ('STANDARD', 'FREE_CLAIM'));

UPDATE gateway.package_instance
SET package_kind = 'FREE_CLAIM'
WHERE package_code = 'FREE';

DROP INDEX IF EXISTS gateway.uq_free_package_per_identity;

CREATE UNIQUE INDEX uq_free_claim_package_per_identity
    ON gateway.package_instance(identity_key)
    WHERE package_kind = 'FREE_CLAIM';
