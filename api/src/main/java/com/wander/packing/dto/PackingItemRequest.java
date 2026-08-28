package com.wander.packing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creating or renaming an item, and saying whose it is.
 *
 * `assigneeUserId` is null for the shared pile, which is a real value here rather
 * than an omission — so this cannot be a partial update, and the whole item is
 * written every time.
 */
public record PackingItemRequest(
        @NotBlank @Size(max = 160) String description,
        Long assigneeUserId) {
}
