-- Record What A Settlement Could Not Collect
--
-- WHY. V51-era settle semantics: a request is admitted only after its worst case is
-- reserved (ReservationCeiling), so a settle can no longer be asked to collect more than was
-- held. It settles what it holds. If the actual usage still outruns the reservation - the
-- gateway's token estimate drifting past the documented pad, or the package's capacity being
-- consumed by a concurrent request between reserve and settle - the settle now:
--
--   1. charges the amount the reservation held (never fails, never strands RESERVED),
--   2. tries to collect the remainder from the customer's available wallet balance,
--   3. and records whatever could not be collected HERE, on the reservation.
--
-- Step 3 is the point of these columns. Without them the shortfall exists only in a log line,
-- and the product rule is that the platform must never absorb provider cost the customer's
-- prepaid balance did not cover - so the amount must be visible in the evidence the economics
-- outbox is built from. gateway.usage_economics_outbox payloads are assembled from this
-- reservation row (UsageEconomicsOutboxRepository.findUsageSnapshot), which makes this the
-- row an operator or a report reads to find money at risk.
--
-- wallet_deficit_amount: retail amount (the price card's unit, per-million-token 分) the
--   customer's balance did not cover. Zero means the settlement was fully collected, whether
--   because the reservation held enough or because step 2 collected the rest.
-- wallet_deficit_tokens: package tokens the package could not be charged for because its
--   capacity was exhausted below what this request had reserved. Kept separately from
--   uncovered_tokens because uncovered_tokens is bounded by capacity and cannot distinguish
--   "the package could not cover it" from "this request had reserved it and lost the race".
--
-- Both are additive with a zero default, so every existing row keeps its meaning: a settled
-- reservation written before this migration was fully collected by construction, because a
-- shortfall used to be impossible to settle at all.

ALTER TABLE gateway.quota_reservation
    ADD COLUMN wallet_deficit_amount bigint NOT NULL DEFAULT 0,
    ADD COLUMN wallet_deficit_tokens bigint NOT NULL DEFAULT 0;

ALTER TABLE gateway.quota_reservation
    ADD CONSTRAINT ck_reservation_wallet_deficit
        CHECK (wallet_deficit_amount >= 0 AND wallet_deficit_tokens >= 0);

COMMENT ON COLUMN gateway.quota_reservation.wallet_deficit_amount IS
    'Retail amount the settlement could not collect from the customer: actual cost above the '
    'reserved amount that the available wallet balance could not cover. Non-zero means real '
    'money at risk and is an operating signal, not a normal state.';

COMMENT ON COLUMN gateway.quota_reservation.wallet_deficit_tokens IS
    'Package tokens the settlement could not charge to the package because its capacity was '
    'exhausted below this request''s reservation.';
