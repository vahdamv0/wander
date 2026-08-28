package com.wander.expense.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Where one person stands.
 *
 * Four components, kept separate on purpose:
 *
 * <pre>
 *   netMinor = (paidMinor + paymentsMadeMinor) - (shareMinor + paymentsReceivedMinor)
 * </pre>
 *
 * `paidMinor` and `shareMinor` are **expenses only**. Folding payments into them
 * would be arithmetically fine and a lie on screen: somebody who fronted a €84.51
 * dinner and was later handed €42.26 back would read "paid €84.51 · share €84.51",
 * when their share of that dinner was €42.25. The number people check a ledger
 * against is their share of the bill, so it stays its own figure.
 *
 * Positive `netMinor` means the trip owes them. Across everybody the nets sum to
 * exactly zero, which is the property the whole feature rests on.
 */
public record PersonBalance(
        @NotNull Long userId,
        @NotNull String displayName,
        /** Expenses this person paid for. Excludes money they handed to somebody. */
        @NotNull long paidMinor,
        /** Their share of the expenses. Excludes money handed to them. */
        @NotNull long shareMinor,
        /** Settling up: what they have paid back to other people. */
        @NotNull long paymentsMadeMinor,
        /** Settling up: what other people have paid them. */
        @NotNull long paymentsReceivedMinor,
        @NotNull long netMinor,
        /**
         * False for somebody who has left the trip but still appears in its
         * expenses. Money is history and membership is present tense, so their
         * rows stay and the client labels them instead of silently dropping a
         * debt.
         */
        @NotNull boolean stillAMember) {
}
