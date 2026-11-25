ALTER TABLE gateway.model_billing_price
    DROP CONSTRAINT ck_model_billing_price_values;

ALTER TABLE gateway.model_billing_price
    ADD CONSTRAINT ck_model_billing_price_values CHECK (
        input_price_per_million > 0 AND output_price_per_million > 0
        AND provider_cost_input_per_million >= 0
        AND provider_cost_output_per_million >= 0
        AND target_margin_ratio >= 0 AND target_margin_ratio < 1
        AND (effective_to IS NULL OR effective_to > effective_from));

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY model_code
               ORDER BY effective_from DESC, created_at DESC, id DESC) AS position
    FROM gateway.model_billing_price
    WHERE effective_to IS NULL
)
UPDATE gateway.model_billing_price price
SET effective_to = GREATEST(now(), price.effective_from + interval '1 microsecond')
FROM ranked
WHERE ranked.id = price.id AND ranked.position > 1;

DROP INDEX gateway.ix_model_billing_price_current;

CREATE UNIQUE INDEX uq_model_billing_price_current
    ON gateway.model_billing_price(model_code)
    WHERE effective_to IS NULL;
