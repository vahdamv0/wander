package com.wander.expense.dto;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import com.wander.expense.Expense;
import com.wander.expense.SplitMode;

import jakarta.validation.constraints.NotNull;

/**
 * One expense as the client draws it.
 *
 * `amountMinor` is an integer count of the currency's smallest unit; the client
 * formats it, because only the client knows the viewer's locale and only
 * `Intl.NumberFormat` reliably knows how many decimal places a currency has.
 *
 * `splitMode` travels so an edit form can reopen in the mode the expense was
 * saved in, rather than turning every equal split into an exact one the first
 * time somebody fixes a typo in the description.
 */
public record ExpenseView(
        @NotNull Long id,
        @NotNull String description,
        @NotNull long amountMinor,
        @NotNull LocalDate spentOn,
        @NotNull Long paidByUserId,
        @NotNull String paidByName,
        @NotNull SplitMode splitMode,
        @NotNull List<ExpenseShareView> shares) {

    public static ExpenseView of(Expense expense) {
        return new ExpenseView(expense.getId(), expense.getDescription(), expense.getAmountMinor(),
                expense.getSpentOn(), expense.getPaidBy().getId(), expense.getPaidBy().getDisplayName(),
                expense.getSplitMode(),
                expense.getShares().stream()
                        .map(ExpenseShareView::of)
                        // Stable order, so a re-read never reshuffles the split on screen.
                        .sorted(Comparator.comparing(ExpenseShareView::displayName))
                        .toList());
    }
}
