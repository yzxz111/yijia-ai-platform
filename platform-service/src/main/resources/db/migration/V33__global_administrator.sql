ALTER TABLE platform.app_user
    ADD COLUMN global_admin boolean NOT NULL DEFAULT false;

UPDATE platform.app_user
SET status = 'ACTIVE', global_admin = true, updated_at = now()
WHERE id = '70000000-0000-4000-8000-000000000001';

DELETE FROM platform.user_role
WHERE user_id = '70000000-0000-4000-8000-000000000001'
  AND role_id <> (SELECT id FROM platform.app_role WHERE code = 'ADMIN');

INSERT INTO platform.user_role(tenant_id, user_id, role_id)
SELECT app_user.tenant_id, app_user.id, app_role.id
FROM platform.app_user app_user
JOIN platform.app_role app_role ON app_role.code = 'ADMIN'
WHERE app_user.id = '70000000-0000-4000-8000-000000000001'
ON CONFLICT (user_id, role_id) DO NOTHING;

CREATE UNIQUE INDEX uq_app_user_single_global_admin
    ON platform.app_user(global_admin)
    WHERE global_admin;

ALTER TABLE platform.app_user
    ADD CONSTRAINT ck_app_user_global_admin_active
        CHECK (NOT global_admin OR status = 'ACTIVE');

CREATE FUNCTION platform.prevent_global_admin_identity_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.global_admin THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'global_admin_immutable'
                USING ERRCODE = '23514';
        END IF;
        IF NOT NEW.global_admin THEN
            RAISE EXCEPTION 'global_admin_immutable'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_app_user_global_admin_immutable
BEFORE UPDATE OF global_admin OR DELETE ON platform.app_user
FOR EACH ROW
EXECUTE FUNCTION platform.prevent_global_admin_identity_change();

CREATE FUNCTION platform.prevent_global_admin_role_removal()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM platform.app_user app_user
        JOIN platform.app_role app_role ON app_role.id = OLD.role_id
        WHERE app_user.id = OLD.user_id
          AND app_user.global_admin
          AND app_role.code = 'ADMIN'
    ) THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'global_admin_role_immutable'
                USING ERRCODE = '23514';
        END IF;
        IF NEW.user_id IS DISTINCT FROM OLD.user_id
                OR NEW.role_id IS DISTINCT FROM OLD.role_id THEN
            RAISE EXCEPTION 'global_admin_role_immutable'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_role_global_admin_immutable
BEFORE UPDATE OR DELETE ON platform.user_role
FOR EACH ROW
EXECUTE FUNCTION platform.prevent_global_admin_role_removal();
