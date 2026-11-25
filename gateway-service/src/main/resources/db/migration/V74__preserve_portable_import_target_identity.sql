-- A staged import must retain the target selected during inspection even if that skill is
-- deleted before commit. The commit path can then return a conflict instead of silently
-- recreating an import whose reviewed target no longer exists.
ALTER TABLE gateway.skill_portable_import
    DROP CONSTRAINT IF EXISTS skill_portable_import_target_skill_id_fkey;
