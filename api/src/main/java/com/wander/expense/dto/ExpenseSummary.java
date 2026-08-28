package com.wander.expense.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * The maths, done once on the server.
 *
 * The client deliberately does not compute any of this. Money arithmetic wants
 * exactly one implementation, and it wants to be the one that has tests around
 * it — a second copy in TypeScript would be a second chance to round a cent
 * differently and a way for the two screens to disagree about who owes whom.
 */
public record ExpenseSummary(
        @NotNull long totalMinor,
        @NotNull List<PersonBalance> balances,
        @NotNull List<SettlementView> settlements) {
}
