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
