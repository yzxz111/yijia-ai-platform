ALTER TABLE platform.grant_request
    DROP CONSTRAINT ck_grant_status;

UPDATE platform.grant_request
SET status = CASE status
        WHEN 'COMPLETED' THEN 'ACTIVE'
        WHEN 'FAILED' THEN 'FAILED'
        ELSE 'QUEUED'
    END;

ALTER TABLE platform.grant_request
    ALTER COLUMN status SET DEFAULT 'QUEUED',
    ADD CONSTRAINT ck_grant_status
        CHECK (status IN ('QUEUED', 'ACTIVE', 'FAILED'));
