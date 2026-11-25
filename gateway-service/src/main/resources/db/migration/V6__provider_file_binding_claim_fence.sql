ALTER TABLE gateway.provider_file_binding
    ADD COLUMN claim_token uuid;

CREATE INDEX ix_provider_file_binding_stale_claim
    ON gateway.provider_file_binding (status, updated_at)
    WHERE status = 'UPLOADING';
