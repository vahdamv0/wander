package com.wander.expense.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Where one person stands.
 *
 * `netMinor` is `paidMinor - shareMinor`: positive means the trip owes them,
 * negative means they owe it. Across everybody the nets sum to exactly zero,
 * which is the arithmetic property the whole feature rests on.
 */
public record PersonBalance(
        @NotNull Long userId,
        @NotNull String displayName,
        @NotNull long paidMinor,
        @NotNull long shareMinor,
        @NotNull long netMinor,
        /**
         * False for somebody who has left the trip but still appears in its
         * expenses. Money is history and membership is present tense, so their
         * rows stay and the client labels them instead of silently dropping a
         * debt.
         */
        @NotNull boolean stillAMember) {
}
