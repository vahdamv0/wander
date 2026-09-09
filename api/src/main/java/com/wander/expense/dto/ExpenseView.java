package com.wander.expense.dto;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import com.wander.expense.Expense;
import com.wander.expense.ExpenseKind;
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
 *
 * **`amountMinor` is always the trip's currency**, converted if it had to be, and
 * it is the only figure the totals and balances are built from. When the money
 * was actually spent in something else the receipt travels beside it — see
 * {@code sourceAmountMinor} — because a ledger that showed only the converted
 * number could not be checked against a bank statement.
 */
public record ExpenseView(
        @NotNull Long id,
        @NotNull String description,
        @NotNull long amountMinor,
        @NotNull LocalDate spentOn,
        @NotNull Long paidByUserId,
        @NotNull String paidByName,
        @NotNull SplitMode splitMode,
        /**
         * PAYMENT for somebody settling up. The client draws those differently and
         * leaves them out of anything it describes as a cost — a payment's single
         * share is the person who received the money, not somebody who owes it.
         */
        @NotNull ExpenseKind kind,
        /**
         * What was handed over and in what, when that was not the trip's currency.
         *
         * Null together with the three fields below, and null is the ordinary
         * case. The client shows the pair — "¥8,000 · €44.68" — rather than
         * either alone: the first is what the person remembers paying and the
         * second is what the split was actually made of.
         */
        Long sourceAmountMinor,
        String sourceCurrency,
        /**
         * The rate that was applied, as a decimal string, frozen at entry.
         *
         * A string for the reason every decimal in this feature is one: a JSON
         * number arrives as a binary float, and a rate has more significant
         * figures than one shows honestly. The client displays it and computes
         * nothing from it — there is one implementation of this arithmetic and it
         * is the one with tests around it.
         */
        String fxRate,
        /**
         * The day the rate was published for — not always the day it was spent,
         * because rates exist on the days somebody publishes them.
         *
         * Null when {@code fxManual} is true: a rate somebody typed has no
         * publication date, and showing one would dress their figure up as a
         * market quote.
         */
        LocalDate fxQuotedOn,
        /**
         * Whether a person typed the rate rather than it being looked up.
         *
         * Worth telling the reader, because the two are different claims: a
         * looked-up rate is a market reference for a date, and a typed one is
         * what somebody's card actually charged. The second is often the more
         * accurate, and the interface should not present them as the same thing.
         */
        @NotNull boolean fxManual,
        @NotNull List<ExpenseShareView> shares) {

    public static ExpenseView of(Expense expense) {
        return new ExpenseView(expense.getId(), expense.getDescription(), expense.getAmountMinor(),
                expense.getSpentOn(), expense.getPaidBy().getId(), expense.getPaidBy().getDisplayName(),
                expense.getSplitMode(), expense.getKind(),
                expense.getSourceAmountMinor(), expense.getSourceCurrency(),
                // toPlainString, not toString: a small enough BigDecimal prints
                // in scientific notation, and "1.18E-5" in a rate field is the
                // sort of thing that gets parsed back as something else.
                expense.getFxRate() == null ? null : expense.getFxRate().toPlainString(),
                expense.getFxQuotedOn(), expense.isFxManual(),
                expense.getShares().stream()
                        .map(ExpenseShareView::of)
                        // Stable order, so a re-read never reshuffles the split on screen.
                        .sorted(Comparator.comparing(ExpenseShareView::displayName))
                        .toList());
    }
}
