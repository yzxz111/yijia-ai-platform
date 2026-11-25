-- Index The Settlement Deficit Window
--
-- WHY. V52 started recording what a settlement could not collect on the reservation itself
-- (wallet_deficit_amount / wallet_deficit_tokens). That made the "how much did the platform
-- absorb" question answerable, and the gateway now answers it at
-- GET /internal/settlement-deficit?from=&to= by summing exactly those two columns over a time
-- window and listing the most recent rows.
--
-- The query has two properties the existing indexes do not serve:
--
--   1. It only ever looks at rows that carry a deficit. On a healthy platform that is a vanishing
--      fraction of quota_reservation, which grows with every request, so the index is partial on
--      the deficit condition rather than carrying the whole table.
--   2. It windows on when the deficit happened. The settlement stamp is settled_at, and the query
--      coalesces created_at for a row that has none, so the index is on that same expression with
--      the same COALESCE - an index on settled_at alone would not be usable by the query as
--      written.
--
-- DESC matches the ORDER BY the endpoint uses to take the most recent 200 rows, so the listing and
-- the totals are both index-ordered.
--
-- No new columns and no data movement: the two columns already exist and every existing row keeps
-- its meaning (V52). This migration only makes the read cheap.

CREATE INDEX ix_reservation_deficit_settled
    ON gateway.quota_reservation (COALESCE(settled_at, created_at) DESC)
    WHERE wallet_deficit_amount > 0 OR wallet_deficit_tokens > 0;

COMMENT ON INDEX gateway.ix_reservation_deficit_settled IS
    'Partial index over the reservations that recorded an uncollectable deficit (V52), keyed by '
    'when it occurred, for the internal settlement-deficit report.';
