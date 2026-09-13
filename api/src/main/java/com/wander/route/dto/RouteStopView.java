package com.wander.route.dto;

import jakarta.validation.constraints.NotNull;

/**
 * One stop in a proposed order.
 *
 * The name rides along so the client can draw the proposal without joining it
 * back to the itinerary it already holds — a list of ids would be a second
 * lookup to get wrong, and the proposal is a thing a person reads.
 *
 * {@code pinned} is why a stop did not move, and the client says which of the
 * two reasons it was: somebody locked it, or it has no coordinates and the
 * routing engine was never told it exists. Being told "these three could not
 * be sorted because they have no location" is the difference between a
 * disappointing result and a mysterious one.
 */
public record RouteStopView(
        @NotNull Long placeId,
        @NotNull String name,
        /** True when this stop stayed where it was — see {@code locked} and {@code located}. */
        @NotNull boolean pinned,
        @NotNull boolean locked,
        @NotNull boolean located) {
}
