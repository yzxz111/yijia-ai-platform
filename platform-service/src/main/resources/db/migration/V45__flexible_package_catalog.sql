ALTER TABLE platform.package_plan
    ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN display_order integer NOT NULL DEFAULT 1000,
    ADD CONSTRAINT ck_package_plan_kind CHECK (kind IN ('STANDARD', 'FREE_CLAIM')),
    ADD CONSTRAINT ck_package_plan_display_order CHECK (display_order >= 0);

UPDATE platform.package_plan
SET kind = CASE WHEN code = 'FREE' THEN 'FREE_CLAIM' ELSE 'STANDARD' END,
    display_order = CASE code
        WHEN 'FREE' THEN 0
        WHEN 'STARTER' THEN 10
        WHEN 'ADVANCED' THEN 20
        WHEN 'ENTERPRISE' THEN 30
        ELSE 1000
    END;

CREATE UNIQUE INDEX uq_active_free_claim_plan
    ON platform.package_plan(kind)
    WHERE kind = 'FREE_CLAIM' AND status = 'ACTIVE';

ALTER TABLE platform.package_plan_version
    ADD COLUMN retired_at timestamptz;

UPDATE platform.package_plan_version
SET retired_at = COALESCE(published_at, created_at, now())
WHERE status = 'RETIRED' AND retired_at IS NULL;

ALTER TABLE platform.package_plan_version
    ADD CONSTRAINT ck_package_version_retired_at CHECK (
        (status = 'RETIRED' AND retired_at IS NOT NULL)
        OR (status <> 'RETIRED' AND retired_at IS NULL)
    );

CREATE OR REPLACE FUNCTION platform.reject_published_version_mutation()
RETURNS trigger AS $$
BEGIN
    IF OLD.status IN ('PUBLISHED', 'RETIRED') THEN
        IF TG_OP = 'UPDATE'
           AND to_jsonb(NEW) - ARRAY['stock_sold', 'stock_reserved']
               IS NOT DISTINCT FROM to_jsonb(OLD) - ARRAY['stock_sold', 'stock_reserved'] THEN
            RETURN NEW;
        END IF;
        IF TG_OP = 'UPDATE'
           AND OLD.status = 'PUBLISHED'
           AND NEW.status = 'RETIRED'
           AND NEW.retired_at IS NOT NULL
           AND to_jsonb(NEW) - ARRAY['status', 'retired_at']
               IS NOT DISTINCT FROM to_jsonb(OLD) - ARRAY['status', 'retired_at'] THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'published_version_immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION platform.reject_published_model_mutation()
RETURNS trigger AS $$
DECLARE
    parent_status varchar(20);
    parent_id uuid;
BEGIN
    parent_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.package_plan_version_id ELSE NEW.package_plan_version_id END;
    SELECT status INTO parent_status
    FROM platform.package_plan_version
    WHERE id = parent_id;
    IF parent_status IN ('PUBLISHED', 'RETIRED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'published_version_immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

WITH ranked_versions AS (
    SELECT v.id,
           row_number() OVER (
               PARTITION BY v.package_plan_id
               ORDER BY v.version DESC, v.created_at DESC, v.id DESC
           ) AS position
    FROM platform.package_plan_version v
    WHERE v.status = 'PUBLISHED'
)
UPDATE platform.package_plan_version v
SET status = 'RETIRED',
    retired_at = now()
FROM ranked_versions ranked
WHERE v.id = ranked.id AND ranked.position > 1;

CREATE UNIQUE INDEX uq_published_package_plan_version
    ON platform.package_plan_version(package_plan_id)
    WHERE status = 'PUBLISHED';
