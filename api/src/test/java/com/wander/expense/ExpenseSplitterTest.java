package com.wander.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The arithmetic, on its own. No database, no context — this is the part of
 * expenses where a bug is silent and permanent, so it is tested harder than the
 * plumbing around it.
 */
class ExpenseSplitterTest {

    @Test
    void anEvenSplitIsEven() {
        assertThat(ExpenseSplitter.equalShares(9000, List.of(1L, 2L, 3L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1L, 3000L, 2L, 3000L, 3L, 3000L));
    }

    @Test
    void theRemainderGoesToTheLowestUserIdsAndNothingIsLost() {
        // The canonical case: 10.00 over three is not three times 3.33.
        Map<Long, Long> shares = ExpenseSplitter.equalShares(1000, List.of(7L, 3L, 5L));

        assertThat(shares).containsEntry(3L, 334L).containsEntry(5L, 333L).containsEntry(7L, 333L);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void anAmountSmallerThanTheGroupGivesSomebodyNothing() {
        // One cent over three people. Two of them owe zero, which is honest — the
        // alternative is inventing two cents.
        Map<Long, Long> shares = ExpenseSplitter.equalShares(1, List.of(1L, 2L, 3L));

        assertThat(shares).containsEntry(1L, 1L).containsEntry(2L, 0L).containsEntry(3L, 0L);
        assertThat(sum(shares)).isEqualTo(1);
    }

    @Test
    void oneParticipantOwesTheWholeThing() {
        assertThat(ExpenseSplitter.equalShares(4703, List.of(9L))).containsExactlyEntriesOf(Map.of(9L, 4703L));
    }

    @Test
    void aSplitWithNobodyInItIsRejected() {
        assertThatThrownBy(() -> ExpenseSplitter.equalShares(1000, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The property that matters more than any individual case: whatever the
     * amount and however many people, the shares add up to the amount and differ
     * from each other by at most one minor unit.
     */
    @ParameterizedTest
    @CsvSource({ "1, 2", "1, 7", "999, 2", "1000, 3", "4703, 6", "100000, 7", "1, 1", "123456789, 13" })
    void sharesAlwaysSumToTheAmount(long amount, int people) {
        List<Long> participants = new ArrayList<>();
        for (long i = 1; i <= people; i++) {
            participants.add(i * 10);
        }

        Map<Long, Long> shares = ExpenseSplitter.equalShares(amount, participants);

        assertThat(shares).hasSize(people);
        assertThat(sum(shares)).isEqualTo(amount);
        long smallest = shares.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        long largest = shares.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(largest - smallest).as("nobody pays more than a minor unit extra").isLessThanOrEqualTo(1);
    }

    @Test
    void aProportionalSplitKeepsTheProportionsAndTheTotal() {
        // ¥8,000 split 5,000 / 3,000, converted to €44.68. Converting the shares
        // one at a time gives 2792 and 1675, which is 4467 — a cent short of the
        // expense it belongs to. Dividing the converted total cannot do that.
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(1L, 5000L);
        typed.put(2L, 3000L);

        Map<Long, Long> shares = ExpenseSplitter.proportionalShares(4468, typed);

        assertThat(shares).containsEntry(1L, 2793L).containsEntry(2L, 1675L);
        assertThat(sum(shares)).isEqualTo(4468);
    }

    @Test
    void aProportionalSplitOfTheSameCurrencyIsTheIdentity() {
        // The case that lets one code path serve both: when the total being
        // divided is already the sum of the weights, everybody gets back exactly
        // what they typed. If this ever stops holding, every ordinary exact split
        // in the application starts being rounded.
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(4L, 3000L);
        typed.put(2L, 2999L);
        typed.put(9L, 1L);

        assertThat(ExpenseSplitter.proportionalShares(6000, typed))
                .containsExactlyInAnyOrderEntriesOf(typed);
    }

    @Test
    void theLeftoverGoesToWhoeverWasRoundedDownHardest() {
        // Three equal weights over a total that does not divide by three. Every
        // remainder ties, so the tie-break decides — lowest user ids first, the
        // same rule equalShares uses.
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(7L, 100L);
        typed.put(3L, 100L);
        typed.put(5L, 100L);

        Map<Long, Long> shares = ExpenseSplitter.proportionalShares(1000, typed);

        assertThat(shares).containsEntry(3L, 334L).containsEntry(5L, 333L).containsEntry(7L, 333L);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void aProportionalSplitSurvivesAmountsThatWouldOverflowALong() {
        // A rupiah trip: the product of the total and one weight is past what a
        // long holds long before either number is unreasonable on its own. Done
        // in long arithmetic this wraps negative, and somebody's share owes money
        // backwards.
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(1L, 6_000_000_000L);
        typed.put(2L, 4_000_000_000L);

        Map<Long, Long> shares = ExpenseSplitter.proportionalShares(10_000_000_000L, typed);

        assertThat(shares).containsEntry(1L, 6_000_000_000L).containsEntry(2L, 4_000_000_000L);
        assertThat(sum(shares)).isEqualTo(10_000_000_000L);
    }

    /**
     * The same property {@code sharesAlwaysSumToTheAmount} pins for an equal
     * split, for the converted one: whatever the total and whatever the
     * proportions, the pieces add up to the whole and none of them is negative.
     */
    @ParameterizedTest
    @CsvSource({ "4468, 5000, 3000", "1, 1, 1", "999, 7, 993", "100000, 1, 999999",
            "3121, 1000, 1000", "7, 3, 3", "123456789, 5, 11" })
    void aProportionalSplitAlwaysSumsToTheTotal(long total, long first, long second) {
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(1L, first);
        typed.put(2L, second);

        Map<Long, Long> shares = ExpenseSplitter.proportionalShares(total, typed);

        assertThat(sum(shares)).isEqualTo(total);
        assertThat(shares.values()).allMatch(share -> share >= 0);
    }

    @Test
    void aNegativeProportionIsRejected() {
        // The API refuses one before it gets here, so this is the guard on the
        // function rather than on the endpoint — and it is not decoration. A
        // negative weight lets the running total overshoot the amount, and the
        // leftover then indexes past the end of the participant list.
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(1L, 5000L);
        typed.put(2L, -1000L);

        assertThatThrownBy(() -> ExpenseSplitter.proportionalShares(4000, typed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNegativeTotalIsRejected() {
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(1L, 5000L);

        assertThatThrownBy(() -> ExpenseSplitter.proportionalShares(-1, typed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proportionsThatAddUpToNothingAreRejected() {
        // Not reachable through the service, which checks the shares against the
        // amount first — and worth refusing here anyway, because the alternative
        // is a division by zero inside the money arithmetic.
        Map<Long, Long> typed = new LinkedHashMap<>();
        typed.put(1L, 0L);
        typed.put(2L, 0L);

        assertThatThrownBy(() -> ExpenseSplitter.proportionalShares(1000, typed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settlingTwoPeopleIsOneTransfer() {
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(1L, 9732L);
        balances.put(2L, -9732L);

        assertThat(ExpenseSplitter.settle(balances))
                .containsExactly(new ExpenseSplitter.Transfer(2L, 1L, 9732L));
    }

    @Test
    void settlingClearsEveryBalance() {
        // Alice fronted everything; Bob and Cara owe her unequal amounts.
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(1L, 12000L);
        balances.put(2L, -5000L);
        balances.put(3L, -7000L);

        List<ExpenseSplitter.Transfer> transfers = ExpenseSplitter.settle(balances);

        assertThat(transfers).as("at most one fewer transfer than there are people").hasSizeLessThanOrEqualTo(2);

        Map<Long, Long> after = new LinkedHashMap<>(balances);
        for (ExpenseSplitter.Transfer transfer : transfers) {
            after.merge(transfer.fromUserId(), transfer.amountMinor(), Long::sum);
            after.merge(transfer.toUserId(), -transfer.amountMinor(), Long::sum);
        }
        assertThat(after.values()).allMatch(net -> net == 0L);
    }

    @Test
    void aThreeWayTangleStillClears() {
        // Everybody has fronted something and the nets are awkward.
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(1L, -3334L);
        balances.put(2L, 6667L);
        balances.put(3L, -3333L);

        List<ExpenseSplitter.Transfer> transfers = ExpenseSplitter.settle(balances);

        Map<Long, Long> after = new LinkedHashMap<>(balances);
        for (ExpenseSplitter.Transfer transfer : transfers) {
            after.merge(transfer.fromUserId(), transfer.amountMinor(), Long::sum);
            after.merge(transfer.toUserId(), -transfer.amountMinor(), Long::sum);
        }
        assertThat(after.values()).allMatch(net -> net == 0L);
        // Nobody is asked to pay somebody who does not need paying.
        assertThat(transfers).allSatisfy(transfer -> assertThat(transfer.amountMinor()).isPositive());
    }

    @Test
    void nobodyOwesAnybodyWhenEverythingIsAlreadyEven() {
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(1L, 0L);
        balances.put(2L, 0L);

        assertThat(ExpenseSplitter.settle(balances)).isEmpty();
    }

    private static long sum(Map<Long, Long> shares) {
        return shares.values().stream().mapToLong(Long::longValue).sum();
    }
}
