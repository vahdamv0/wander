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
        @NotNull List<PlaceView> places) {
}
