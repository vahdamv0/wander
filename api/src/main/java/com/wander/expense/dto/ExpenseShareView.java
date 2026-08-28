package com.wander.expense.dto;

import com.wander.expense.ExpenseShare;

import jakarta.validation.constraints.NotNull;

/** What one participant owes for one expense, in the trip's minor units. */
public record ExpenseShareView(
        @NotNull Long userId,
        @NotNull String displayName,
        @NotNull long amountMinor) {

    public static ExpenseShareView of(ExpenseShare share) {
        return new ExpenseShareView(share.getUser().getId(), share.getUser().getDisplayName(),
                share.getAmountMinor());
    }
}
