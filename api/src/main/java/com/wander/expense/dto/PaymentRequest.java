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
        /** Minor units, and more than nothing. */
        @NotNull @Min(1) Long amountMinor,
        @NotNull LocalDate paidOn,
        /** Optional: "cash at the airport". Shown instead of the default label. */
        @Size(max = 160) String note) {
}
