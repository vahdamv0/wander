package com.wander.expense;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The money arithmetic, with no database and no Spring anywhere near it — which
 * is the point: this is the part of the feature that is worth testing
 * exhaustively, and it is a pure function of its inputs.
 *
 * Everything is in integer minor units. There is no floating point in this class
 * and there must not be: a tenth of a cent has no representation in a bank
 * account, and `0.1 + 0.2` is the oldest bug in the book.
 */
public final class ExpenseSplitter {

    private ExpenseSplitter() {
    }

    /**
     * Splits an amount evenly, to the minor unit, with **no money lost or
     * invented**: `sum(result) == amount` exactly, always.
     *
     * €10.00 over three people is 3.34 / 3.33 / 3.33, not three times 3.33 with a
     * cent unaccounted for. The remainder goes one minor unit at a time to the
     * participants with the lowest user ids — an arbitrary rule, but a
     * *deterministic* one, so the same expense split twice gives the same answer
     * and a test can assert on it. The unfairness is at most one minor unit per
     * person, which is the best any integer split can do.
     */
    public static Map<Long, Long> equalShares(long amount, List<Long> participantUserIds) {
        if (participantUserIds.isEmpty()) {
            throw new IllegalArgumentException("An expense needs at least one participant");
        }
        List<Long> ordered = participantUserIds.stream().sorted().toList();
        int people = ordered.size();
        long base = amount / people;
        // Positive amounts only, so this is a plain remainder; no floor/truncate
        // divergence to worry about.
        long remainder = amount % people;

        Map<Long, Long> shares = new LinkedHashMap<>();
        for (int i = 0; i < people; i++) {
            shares.put(ordered.get(i), i < remainder ? base + 1 : base);
        }
        return shares;
    }

    /**
     * Who should pay whom to clear the balances, in as few transfers as possible.
     *
     * Greedy: repeatedly settle the largest debt against the largest credit. That
     * empties at least one party per transfer, so it never needs more than n-1 of
     * them — which is the fewest possible when everybody's balance differs. It is
     * not the *minimum* over all inputs (that problem is NP-hard, and finding a
     * three-way cycle to cancel is not worth it for a holiday), but it is always
     * correct: after applying every transfer, every balance is zero.
     *
     * `balances` is net per person: positive is owed money, negative owes it.
     */
    public static List<Transfer> settle(Map<Long, Long> balances) {
        List<long[]> creditors = new ArrayList<>();
        List<long[]> debtors = new ArrayList<>();
        balances.forEach((userId, net) -> {
            if (net > 0) {
                creditors.add(new long[] { userId, net });
            } else if (net < 0) {
                debtors.add(new long[] { userId, -net });
            }
        });

        // Largest first, and ties broken by user id so the output is stable
        // rather than dependent on map iteration order.
        Comparator<long[]> largestFirst = Comparator.<long[]>comparingLong(entry -> -entry[1])
                .thenComparingLong(entry -> entry[0]);
        creditors.sort(largestFirst);
        debtors.sort(largestFirst);

        List<Transfer> transfers = new ArrayList<>();
        int creditor = 0;
        int debtor = 0;
        while (creditor < creditors.size() && debtor < debtors.size()) {
            long[] owed = creditors.get(creditor);
            long[] owes = debtors.get(debtor);
            long amount = Math.min(owed[1], owes[1]);

            transfers.add(new Transfer(owes[0], owed[0], amount));
            owed[1] -= amount;
            owes[1] -= amount;
            if (owed[1] == 0) {
                creditor++;
            }
            if (owes[1] == 0) {
                debtor++;
            }
        }
        return transfers;
    }

    /** One payment: `from` hands `amount` to `to`. User ids, resolved to names above. */
    public record Transfer(Long fromUserId, Long toUserId, long amountMinor) {
    }
}
