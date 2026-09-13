package com.wander.route.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * A better order for one day's stops — proposed, never applied.
 *
 * **Nothing is written to produce this**, which is the whole shape of the
 * feature and the same bargain booking import makes. The client shows the
 * proposal, and applying it is an ordinary reorder through
 * {@code reorderDay} — one write path, one set of rules, and a misread route
 * costs a Discard rather than somebody's rearranged itinerary appearing on
 * every other member's screen a second later with no undo.
 *
 * The two totals are what makes the proposal judgeable: an order that saves
 * four minutes is not worth the click, and only the numbers can say so.
 */
public record DayRoutePreview(
        @NotNull LocalDate dayDate,
        @NotNull String profile,
        /** The proposed order, first stop first. */
        @NotNull List<RouteStopView> stops,
        /** Travel time of the day as it stands, in seconds. */
        @NotNull long currentSeconds,
        /** Travel time of the proposed order, in seconds. */
        @NotNull long proposedSeconds,
        /** Distance of the proposed order, in metres. */
        @NotNull long proposedMetres,
        /**
         * False when the proposal is the order the day is already in. The
         * client says so rather than offering an Apply that changes nothing —
         * "already the best order I can find" is a useful answer, and a button
         * that appears to do nothing is not.
         */
        @NotNull boolean changed,
        @NotNull String attribution,
        @NotNull String attributionUrl) {
}
