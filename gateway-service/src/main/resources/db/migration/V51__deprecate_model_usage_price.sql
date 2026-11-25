-- Mark The Dead Price Table Deprecated
--
-- WHY. gateway.model_usage_price is read by no Java code. Billing reads
-- gateway.model_billing_price: ModelBillingPriceRepository is the only price repository in
-- the gateway, and it selects from model_billing_price alone. model_usage_price is left over
-- from the pricing design that model_billing_price replaced, and it still holds 13 rows that
-- look authoritative to anyone reading the schema.
--
-- That is the hazard this migration addresses and nothing else. A table that no code reads
-- but that still carries plausible numbers is a trap: the next person to change a price, or
-- to debug a charge, will find it and believe it. The comment below is the signpost, and it
-- names the table that is actually authoritative so the reader does not have to grep the
-- repository to find out.
--
-- The table is NOT dropped and its data is NOT touched. Dropping it is a separate decision
-- with a separate review: it is still reachable by hand, may be referenced by reports or
-- tooling outside this repository, and this migration has no way to prove otherwise. A
-- deprecation that is reversible by deleting one COMMENT is the right size for the evidence
-- available.
--
-- Documentation language. The gateway's SQL comments are English (see V48, V49, V50); the
-- schema docs under docs/ carry Chinese where the product vocabulary is Chinese. This
-- migration is a schema comment read by engineers working in these files, so it is English,
-- matching the migration it sits beside. The column comments below are added only where the
-- table's own comment can name them: the sync/publication columns, which exist to feed
-- model_billing_price and are the specific reason a reader might mistake this table for live.

COMMENT ON TABLE gateway.model_usage_price IS
    'DEPRECATED (see gateway migration V51). No Java code reads this table. It is a leftover '
    'of the pricing design that gateway.model_billing_price replaced, and it is retained only '
    'so its historical rows stay queryable by hand. gateway.model_billing_price is the '
    'AUTHORITATIVE retail card: every charge the gateway makes resolves through '
    'ModelBillingPriceService, which reads model_billing_price and never this table. Do not '
    'add rows here and do not change a price here expecting it to take effect - it will not.';

COMMENT ON COLUMN gateway.model_usage_price.price_version IS
    'DEPRECATED along with gateway.model_usage_price (V51). Price versions that billing '
    'actually resolves live on gateway.model_billing_price.price_version.';

COMMENT ON COLUMN gateway.model_usage_price.effective_from IS
    'DEPRECATED along with gateway.model_usage_price (V51). The effective window that billing '
    'honours is gateway.model_billing_price.effective_from / effective_to.';

COMMENT ON COLUMN gateway.model_usage_price.effective_to IS
    'DEPRECATED along with gateway.model_usage_price (V51). The effective window that billing '
    'honours is gateway.model_billing_price.effective_from / effective_to.';

COMMENT ON COLUMN gateway.model_usage_price.published_by IS
    'DEPRECATED along with gateway.model_usage_price (V51). Publications that reach customers '
    'are written to gateway.model_billing_price.';
