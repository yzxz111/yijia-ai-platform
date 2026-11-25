ALTER TABLE platform.content_safety_case
    ADD COLUMN resolution_action varchar(32);

ALTER TABLE platform.content_safety_case
    ADD CONSTRAINT ck_content_safety_case_resolution_action
    CHECK (resolution_action IS NULL
        OR resolution_action IN ('FALSE_POSITIVE', 'ACCOUNT_BANNED', 'RECORDED'));
