package com.wander.place.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Where a place should end up. One shape covers both today's up/down buttons and
 * the drag-and-drop that replaces them: a target day and a target rank within
 * it. A `position` past the end of the day clamps to the end rather than
 * failing, so the client never has to know how long the day is.
 */
public record MovePlaceRequest(
        @NotNull LocalDate dayDate,
        @NotNull @Min(0) Integer position) {
}
