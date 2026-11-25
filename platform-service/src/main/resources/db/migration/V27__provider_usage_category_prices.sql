ALTER TABLE platform.provider_price_snapshot
    ADD COLUMN cache_read_price numeric(20, 6),
    ADD COLUMN cache_write_price numeric(20, 6),
    ADD COLUMN reasoning_price numeric(20, 6),
    ADD CONSTRAINT ck_provider_category_prices CHECK (
        (cache_read_price IS NULL
            OR (cache_read_price >= 0 AND cache_read_price < 'Infinity'::numeric))
        AND (cache_write_price IS NULL
            OR (cache_write_price >= 0 AND cache_write_price < 'Infinity'::numeric))
        AND (reasoning_price IS NULL
            OR (reasoning_price >= 0 AND reasoning_price < 'Infinity'::numeric))
    );
