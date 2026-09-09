package com.wander.expense.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One participant in a write.
 *
 * `amountMinor` is required for an EXACT split and ignored for an EQUAL one,
 * where the server works the amounts out. It is in the **expense's** currency,
 * not necessarily the trip's — the shares are argued over in whatever the bill
 * was in, and the server converts the total once and divides that. It is not two request types because
 * that would be two endpoints or a polymorphic body, and the generated client
 * handles neither gracefully.
 */
public record ExpenseShareInput(
        @NotNull Long userId,
        @Min(0) Long amountMinor) {
}
