package com.wander.expense.dto;

import java.time.LocalDate;
import java.util.List;

import com.wander.expense.SplitMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create or update — the same body, because an expense is written whole. A split
 * is not something you patch: changing who was in it changes everybody's share.
 *
 * The amount arrives already in minor units. The client does that conversion
 * because it is the side that knows the currency's exponent, and sending "12.34"
 * as a decimal would put a floating-point value on the wire in the one feature
 * that must not have one.
 */
public record ExpenseRequest(
        @NotBlank @Size(max = 160) String description,
        /** Minor units, and more than nothing: a zero-cost expense is a note, not an expense. */
        @NotNull @Min(1) Long amountMinor,
        @NotNull LocalDate spentOn,
        @NotNull Long paidByUserId,
        @NotNull SplitMode splitMode,
        /** Who shares it. For EXACT, each entry's amount must be present and they must sum to the total. */
        @NotEmpty List<@Valid ExpenseShareInput> shares) {
}
