DROP TRIGGER IF EXISTS trg_app_user_user_no_immutable ON platform.app_user;

ALTER TABLE platform.app_user
    ALTER COLUMN user_no TYPE varchar(9);

ALTER TABLE platform.app_user
    DROP CONSTRAINT ck_app_user_user_no_format;

ALTER TABLE platform.app_user
    ADD CONSTRAINT ck_app_user_user_no_format
        CHECK (user_no ~ '^U[0-9A-Z]{8}$');

ALTER SEQUENCE platform.app_user_no_seq
    OWNED BY NONE;

CREATE FUNCTION platform.generate_user_no()
RETURNS varchar(9)
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    random_bytes bytea;
    candidate varchar(9);
    alphabet text := '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    first_alphabet text := 'ABCDEFGHJKLMNPQRSTUVWXYZ';
    byte_index integer;
BEGIN
    LOOP
        random_bytes := uuid_send(gen_random_uuid());
        candidate := 'U' || substr(
            first_alphabet,
            (get_byte(random_bytes, 0) % length(first_alphabet)) + 1,
            1);
        FOR byte_index IN 1..7 LOOP
            candidate := candidate || substr(
                alphabet,
                (get_byte(random_bytes, byte_index) % length(alphabet)) + 1,
                1);
        END LOOP;
        EXIT WHEN NOT EXISTS (
            SELECT 1 FROM platform.app_user WHERE user_no = candidate
        );
    END LOOP;
    RETURN candidate;
END;
$$;

ALTER TABLE platform.app_user
    ALTER COLUMN user_no SET DEFAULT
        platform.generate_user_no();

CREATE TRIGGER trg_app_user_user_no_immutable
BEFORE UPDATE OF user_no ON platform.app_user
FOR EACH ROW
EXECUTE FUNCTION platform.prevent_app_user_no_update();
