ALTER TABLE gateway.file_object
    ADD COLUMN deletion_claim_token uuid;

CREATE INDEX ix_file_object_stale_deletion
    ON gateway.file_object (status, updated_at)
    WHERE status = 'DELETING';
