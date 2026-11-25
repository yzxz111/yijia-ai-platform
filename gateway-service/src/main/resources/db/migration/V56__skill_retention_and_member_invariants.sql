-- Enforce the roster invariant under concurrent writers and let immutable download log snapshots
-- outlive a skill that has reached its permanent-deletion retention boundary.

DROP INDEX gateway.ix_skill_member_lead;

CREATE UNIQUE INDEX ux_skill_member_one_lead
    ON gateway.skill_member (skill_id)
    WHERE member_role = 'LEAD';

ALTER TABLE gateway.skill_download
    DROP CONSTRAINT skill_download_skill_id_fkey,
    DROP CONSTRAINT skill_download_skill_version_id_fkey,
    ALTER COLUMN skill_id DROP NOT NULL,
    ALTER COLUMN skill_version_id DROP NOT NULL;

ALTER TABLE gateway.skill_download
    ADD CONSTRAINT skill_download_skill_id_fkey
        FOREIGN KEY (skill_id) REFERENCES gateway.skill (id) ON DELETE SET NULL,
    ADD CONSTRAINT skill_download_skill_version_id_fkey
        FOREIGN KEY (skill_version_id) REFERENCES gateway.skill_version (id) ON DELETE SET NULL;
