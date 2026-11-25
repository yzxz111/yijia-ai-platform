CREATE SEQUENCE platform.app_user_no_seq
    AS bigint
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 99999999
    NO CYCLE;

ALTER TABLE platform.app_user
    ADD COLUMN user_no varchar(9);

ALTER TABLE platform.app_user
    ALTER COLUMN user_no SET DEFAULT
        ('U' || lpad(nextval('platform.app_user_no_seq')::text, 8, '0'));

UPDATE platform.app_user
SET user_no = DEFAULT
WHERE user_no IS NULL;

ALTER TABLE platform.app_user
    ALTER COLUMN user_no SET NOT NULL,
    ADD CONSTRAINT ck_app_user_user_no_format
        CHECK (user_no ~ '^U[0-9]{8}$');

CREATE UNIQUE INDEX uq_app_user_user_no
    ON platform.app_user(user_no);

ALTER SEQUENCE platform.app_user_no_seq
    OWNED BY platform.app_user.user_no;

CREATE FUNCTION platform.prevent_app_user_no_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.user_no IS DISTINCT FROM OLD.user_no THEN
        RAISE EXCEPTION 'app_user_user_no_immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_app_user_user_no_immutable
BEFORE UPDATE OF user_no ON platform.app_user
FOR EACH ROW
EXECUTE FUNCTION platform.prevent_app_user_no_update();
