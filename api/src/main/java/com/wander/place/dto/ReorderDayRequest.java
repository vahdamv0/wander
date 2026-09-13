package com.wander.place.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * The order a day's places should be in, first stop first.
 *
 * The **whole day**, not a patch: the ids must be exactly the ids on that day,
 * each once. A partial list would need a rule for where the rest go, and every
 * such rule is a way for a reorder that half-applied to look like it worked.
 * It is the same argument `place_note` makes about being written whole.
 */
public record ReorderDayRequest(@NotNull @NotEmpty List<Long> placeIds) {
}
