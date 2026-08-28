package com.wander.packing.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * One person's section of the list, or the shared one.
 *
 * Grouped on the server rather than in the client — not because grouping is hard,
 * but because the server is what knows the member list, and a member who has
 * added nothing yet still needs a section. An absent section reads as missing
 * data; an empty one reads as an empty one. The same reasoning puts current
 * members into `ExpenseSummary.balances` at zero.
 */
public record PackingGroup(
        /** Null for the shared group; the client supplies its label. */
        Long userId,
        /** Null for the shared group. */
        String displayName,
        /** False for somebody who has left the trip but whose items are still listed. */
        @NotNull boolean stillAMember,
        @NotNull List<PackingItemView> items,
        @NotNull int packedCount) {
}
