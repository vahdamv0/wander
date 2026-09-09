package com.wander.expense.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One person settling up with another.
 *
 * Its own request shape rather than an `ExpenseRequest` with a kind on it,
 * because a caller should not have to know that a payment is stored as an
 * expense whose single share belongs to the recipient. Getting that inside out
 * would silently reverse a balance, so the server derives it instead.
 *
 * There is no update: a payment has nothing to correct except who, how much and
 * when, which is the whole row. Fixing one is removing it and recording it again.
 */
public record PaymentRequest(
        @NotNull Long fromUserId,
        @NotNull Long toUserId,
        /** Minor units of {@code currency}, and more than nothing. */
        @NotNull @Min(1) Long amountMinor,
        /**
         * What actually changed hands, ISO 4217. Optional, meaning the trip's own
         * currency.
         *
         * Settling up in a foreign currency is not an edge case: somebody hands
         * over cash abroad, in the cash they have. It converts exactly as an
         * expense does — once, frozen, stored in the trip's currency — so the
         * balance it clears is the balance it was meant to clear.
         */
        @Size(min = 3, max = 3) String currency,
        /** A rate to use instead of looking one up, as a decimal string. See {@code ExpenseRequest}. */
        String fxRate,
        @NotNull LocalDate paidOn,
        /** Optional: "cash at the airport". Shown instead of the default label. */
        @Size(max = 160) String note) {
}
