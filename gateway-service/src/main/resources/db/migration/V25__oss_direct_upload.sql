ALTER TABLE gateway.file_object
    ADD COLUMN api_key_id uuid REFERENCES gateway.api_key(id) ON DELETE RESTRICT,
    ADD COLUMN upload_session_id varchar(128),
    ADD COLUMN upload_idempotency_key varchar(128),
    ADD COLUMN upload_expires_at timestamptz,
    ADD COLUMN declared_sha256 varchar(64),
    ADD COLUMN etag varchar(512),
    ADD COLUMN quarantine_object_key varchar(512),
    ADD COLUMN scan_claim_token uuid,
    ADD COLUMN scan_claimed_at timestamptz,
    ADD COLUMN scan_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN next_scan_at timestamptz,
    ADD COLUMN last_error_code varchar(128),
    ADD COLUMN quarantine_cleanup_claim_token uuid,
    ADD COLUMN quarantine_cleanup_claimed_at timestamptz;

ALTER TABLE gateway.file_object
    DROP CONSTRAINT ck_file_status,
    ADD CONSTRAINT ck_file_status CHECK (status IN (
        'UPLOADING', 'PENDING_UPLOAD', 'UPLOADED', 'SCANNING', 'READY',
        'REJECTED', 'FAILED', 'DELETING', 'DELETED'
    )),
    ADD CONSTRAINT ck_file_declared_sha256 CHECK (
        declared_sha256 IS NULL OR declared_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_file_scan_attempts CHECK (scan_attempts >= 0);

CREATE UNIQUE INDEX uq_file_upload_idempotency
    ON gateway.file_object(api_key_id, upload_idempotency_key)
    WHERE upload_idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_file_upload_session
    ON gateway.file_object(upload_session_id)
    WHERE upload_session_id IS NOT NULL;

CREATE INDEX ix_file_pending_upload_cleanup
    ON gateway.file_object(
        (COALESCE(upload_expires_at, expires_at)), created_at, id)
    WHERE status = 'PENDING_UPLOAD';

CREATE INDEX ix_file_scan_claim
    ON gateway.file_object(status, next_scan_at, scan_claimed_at, created_at)
    WHERE status IN ('UPLOADED', 'SCANNING');

CREATE INDEX ix_file_quarantine_cleanup
    ON gateway.file_object(created_at, id)
    INCLUDE (quarantine_cleanup_claim_token, quarantine_cleanup_claimed_at)
    WHERE quarantine_object_key IS NOT NULL
      AND status IN ('READY', 'REJECTED', 'FAILED');
