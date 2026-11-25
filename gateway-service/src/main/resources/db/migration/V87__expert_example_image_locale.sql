-- Optional language ownership for the Expert example image.
-- NULL preserves profiles written before this field existed; desktop clients interpret it as zh.
ALTER TABLE gateway.skill_expert_profile
    ADD COLUMN example_image_locale text;

ALTER TABLE gateway.skill_expert_profile
    ADD CONSTRAINT ck_skill_expert_profile_example_image_locale
        CHECK (example_image_locale IS NULL OR example_image_locale IN ('zh', 'en', 'neutral'));

COMMENT ON COLUMN gateway.skill_expert_profile.example_image_locale IS
    'Optional language ownership of the example image: zh, en, or neutral; NULL is the legacy zh default.';
