package com.wander.expense.dto;

import jakarta.validation.constraints.NotNull;

/**
 * One suggested payment that would clear part of the balance.
 *
 * Advisory only: nothing records that it happened yet, so these do not shrink
 * until the expenses behind them change. Recording a payment is the next commit.
 */
public record SettlementView(
        @NotNull Long fromUserId,
        @NotNull String fromName,
        @NotNull Long toUserId,
        @NotNull String toName,
        @NotNull long amountMinor) {
}
