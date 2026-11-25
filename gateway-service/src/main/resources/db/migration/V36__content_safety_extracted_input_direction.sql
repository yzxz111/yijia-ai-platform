ALTER TABLE gateway.content_safety_check
    DROP CONSTRAINT ck_content_safety_check_direction;

ALTER TABLE gateway.content_safety_check
    ADD CONSTRAINT ck_content_safety_check_direction
    CHECK (direction IN ('INPUT', 'INPUT_EXTRACTED', 'OUTPUT', 'FILE'));
