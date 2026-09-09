-- Paying for something in a currency that is not the trip's.
--
-- `V6` put one currency on the trip and said why: "balances that mix currencies
-- need a rate and a date and stop being arithmetic". That is still true, and it
-- is what shapes this migration. The rate and the date are stored, **on the row,
-- frozen at entry** — and the balances go on being arithmetic in exactly one
-- currency, because the conversion happens once, on the way in.
--
-- So `expenses.amount_minor` does not change meaning: it is still the trip's
-- currency, still what every aggregate sums, and `sumPerDay` and the balance
-- summary carry on reading it without knowing this feature exists. What is added
-- beside it is what the payer actually handed over. All of it is nullable, and
-- NULL means "this was paid in the trip's own currency" — which is every row
-- that exists today, so there is nothing to backfill and no window in which a
-- half-migrated table means something different.

-- What was really paid, when it was not in the trip's currency. Kept because it
-- is the number on the receipt: converting it away and showing only the result
-- would make the ledger impossible to check against a bank statement, which is
-- most of what anybody does with one after a trip.
ALTER TABLE expenses ADD COLUMN source_amount_minor BIGINT
    CHECK (source_amount_minor IS NULL OR source_amount_minor > 0);
ALTER TABLE expenses ADD COLUMN source_currency VARCHAR(3);

-- The rate that was applied, and it is **frozen**: it is not looked up again on
-- an edit, because the euros left the account at the rate of the day and no
-- later rate makes that untrue. Editing a description must not move a balance.
--
-- NUMERIC, not a float, for the reason the whole money feature is integers: this
-- is the one value in the schema that genuinely cannot be an integer, so it gets
-- the exact decimal type rather than the approximate one.
--
-- The scale is deliberately generous. A cross rate between a strong and a weak
-- currency is a very small number — KWD to VND lands around 0.0000118 — and a
-- rate rounded to a few places would visibly fail to reproduce the amount beside
-- it. It is stored at this scale and the conversion is computed *from the stored
-- value*, so `source_amount x fx_rate = amount_minor` holds for the numbers a
-- person can actually see. `amount_minor` remains the authority either way.
ALTER TABLE expenses ADD COLUMN fx_rate NUMERIC(30, 15)
    CHECK (fx_rate IS NULL OR fx_rate > 0);

-- The date the rate was published for, which is not always the date asked for:
-- a rate is a thing that exists on trading days, and an upstream answering for a
-- Saturday may be answering with Friday's. Storing what was asked for would be
-- storing a small lie, and this column is the only thing that can ever say when
-- the number came from.
ALTER TABLE expenses ADD COLUMN fx_quoted_on DATE;

-- Whether a person typed this rate instead of it being looked up.
--
-- Worth a column rather than being inferred from anything, because the two are
-- different claims and the interface says which: a looked-up rate is a market
-- reference for a date, and a typed one is what somebody's card actually charged
-- them. The second is frequently the more accurate of the two, and it is the
-- only one available at all on an instance with no outbound network.
ALTER TABLE expenses ADD COLUMN fx_manual BOOLEAN NOT NULL DEFAULT FALSE;

-- Either all of it or none of it. A source amount with no rate is a row nothing
-- can interpret, and a rate with no source amount is a rate applied to nothing —
-- both are the shape a half-finished write would leave behind, so the database
-- refuses them rather than leaving a service to remember.
ALTER TABLE expenses ADD CONSTRAINT ck_expenses_fx CHECK (
    (source_amount_minor IS NULL AND source_currency IS NULL AND fx_rate IS NULL
        AND fx_quoted_on IS NULL AND fx_manual = FALSE)
    OR (source_amount_minor IS NOT NULL AND source_currency IS NOT NULL AND fx_rate IS NOT NULL)
);

-- Rates against the euro, one row per currency per day.
--
-- A cache, like `place_enrichment` and `day_weather`, but with a property
-- neither of those has: **a published rate for a past date never changes**. So a
-- hit is not merely fresh, it is permanently correct, and there is no TTL here
-- on purpose. That is what makes an outage mostly invisible — a trip whose
-- expenses are being entered a few days after the fact asks about dates this
-- instance has usually already seen.
--
-- Keyed against the euro rather than as a pair, because the pair a trip needs is
-- a division of two of these: caching JPY->EUR would mean caching JPY->GBP
-- separately, while caching JPY and GBP against the euro answers both and makes
-- a trip's second foreign currency half a lookup instead of a whole one.
--
-- Global, not per trip or per user: 179.11 yen to the euro on the 9th of
-- September is not anybody's fact in particular.
CREATE TABLE fx_rates (
    id           BIGINT         GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    -- The date this rate is being used *for* — what was asked. The cache key.
    rate_date    DATE           NOT NULL,
    -- VARCHAR(3), not CHAR(3), for the reason `V6` gives about `trips.currency`:
    -- Postgres pads a CHAR and 'EUR ' does not equal 'EUR'.
    currency     VARCHAR(3)     NOT NULL,
    -- How many of this currency one euro buys. Always the direction with the
    -- larger number, which is not a stylistic choice: the upstream publishes
    -- about five significant figures whichever way it is asked, so the inverted
    -- direction arrives already rounded — 0.0000330 for the dong, two figures,
    -- an error of over one percent. Asking against the euro gets 30127.
    rate_per_eur NUMERIC(30, 15) NOT NULL CHECK (rate_per_eur > 0),
    -- What the upstream said the rate's own date was; see `fx_quoted_on` above.
    quoted_on    DATE           NOT NULL,
    fetched_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_fx_rates UNIQUE (rate_date, currency)
);
