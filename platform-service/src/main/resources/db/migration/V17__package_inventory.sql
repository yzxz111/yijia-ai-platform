ALTER TABLE platform.package_plan_version
    ADD COLUMN stock_total bigint,
    ADD COLUMN stock_sold bigint NOT NULL DEFAULT 0,
    ADD COLUMN stock_reserved bigint NOT NULL DEFAULT 0,
    ADD COLUMN low_stock_threshold bigint NOT NULL DEFAULT 0,
    ADD COLUMN max_purchases_per_user integer,
    ADD CONSTRAINT ck_package_version_inventory CHECK (
        (stock_total IS NULL OR stock_total >= 0)
        AND stock_sold >= 0
        AND stock_reserved >= 0
        AND (stock_total IS NULL OR stock_sold + stock_reserved <= stock_total)
        AND low_stock_threshold >= 0
        AND (stock_total IS NULL OR low_stock_threshold <= stock_total)
        AND (max_purchases_per_user IS NULL OR max_purchases_per_user > 0)
    );

ALTER TABLE platform.customer_order
    ADD COLUMN inventory_status varchar(16) NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT ck_order_inventory_status CHECK (
        inventory_status IN ('NONE', 'RESERVED', 'SOLD', 'RELEASED')
    );

CREATE INDEX ix_order_inventory_user_version
    ON platform.customer_order(user_id, package_plan_version_id, inventory_status);

CREATE INDEX ix_package_version_inventory
    ON platform.package_plan_version(status, stock_total, stock_sold, stock_reserved);

-- Inventory counters are operational state and must change after publication;
-- all catalog and entitlement fields remain immutable.
CREATE OR REPLACE FUNCTION platform.reject_published_version_mutation()
RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        IF TG_OP = 'UPDATE'
           AND to_jsonb(NEW) - ARRAY['stock_sold', 'stock_reserved']
               IS NOT DISTINCT FROM to_jsonb(OLD) - ARRAY['stock_sold', 'stock_reserved'] THEN
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
