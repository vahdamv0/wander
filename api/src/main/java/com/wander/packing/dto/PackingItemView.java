package com.wander.packing.dto;

import com.wander.packing.PackingItem;

import jakarta.validation.constraints.NotNull;

/** One thing to bring, as the client draws it. */
public record PackingItemView(
        @NotNull Long id,
        @NotNull String description,
        /** Null for a shared item. Present so an edit form knows where it started. */
        Long assigneeUserId,
        @NotNull boolean packed,
        /** Who ticked it — the useful half of "packed" on a shared item. Null when unpacked. */
        String packedByName) {

    public static PackingItemView of(PackingItem item) {
        return new PackingItemView(item.getId(), item.getDescription(),
                item.getAssignee() == null ? null : item.getAssignee().getId(),
                item.isPacked(),
                item.getPackedBy() == null ? null : item.getPackedBy().getDisplayName());
    }
}
