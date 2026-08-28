-- Recording that somebody actually settled up.
--
-- No new table, and that is the point. A payment is an expense row seen from a
-- different angle: `paid_by_user_id` is whoever handed the money over, and its
-- single share belongs to whoever received it. The balance arithmetic then needs
-- no special case at all —
--
--   net[u] = paid[u] - owed[u]
--
-- so Bob paying Alice 40 adds 40 to Bob's `paid` and 40 to Alice's `owed`, and
-- two balances of -40 and +40 become zero. A separate `payments` table would have
-- meant a second thing to sum and a second way for the two sums to disagree.
--
-- What the kind *is* needed for is the trip total: what a holiday cost is the sum
-- of its expenses, and money moving between the people on it is not a cost.

ALTER TABLE expenses ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'EXPENSE';

-- Every existing row is a real expense; payments could not be recorded before
-- this migration. The default stays so the column is not a burden on inserts
-- that predate the enum.

-- The summary reads expenses and payments together, and the ledger shows both,
-- so nothing here wants an index on kind: the existing (trip_id, spent_on) index
-- already answers the only query.
