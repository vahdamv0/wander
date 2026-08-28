package com.wander.place.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * A derived day. `index` is 1-based so the client can print "Day 3" without
 * doing arithmetic on dates, and it is computed from the trip's start date on
 * every read rather than stored.
 */
public record TripDay(
        @NotNull LocalDate date,
        @NotNull int index,
        @NotNull List<PlaceView> places,
        /** The day's own note, or null when it has none. Most days have none. */
        String note,
        /**
         * What was spent on this day, in the trip's minor units, or null when
         * nothing was — payments excluded, as in the trip total.
         *
         * Null rather than zero, and the distinction is the point: zero would have
         * the day card print "0.00" on every day of a trip nobody has recorded
         * money for, which is a claim about the world rather than an absence of
         * one.
         */
        Long spentMinor) {
}
