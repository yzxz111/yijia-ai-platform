ALTER TABLE platform.content_safety_case
    DROP CONSTRAINT ck_content_safety_case_direction;

ALTER TABLE platform.content_safety_case
    ADD CONSTRAINT ck_content_safety_case_direction
    CHECK (direction IN ('INPUT', 'INPUT_EXTRACTED', 'OUTPUT', 'FILE'));
