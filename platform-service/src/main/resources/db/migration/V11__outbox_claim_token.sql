ALTER TABLE platform.outbox_event
    ADD COLUMN claim_token uuid;

UPDATE platform.outbox_event
SET status = 'PENDING',
    claim_token = NULL,
    next_attempt_at = now()
WHERE status = 'DELIVERING';

ALTER TABLE platform.outbox_event
    ADD CONSTRAINT ck_outbox_claim_token CHECK (
        (status = 'DELIVERING' AND claim_token IS NOT NULL)
        OR (status <> 'DELIVERING' AND claim_token IS NULL)
    );
