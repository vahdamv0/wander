package com.wander.place.dto;

import java.util.List;

import com.wander.trip.dto.TripSummary;

import jakarta.validation.constraints.NotNull;

/**
 * Everything the trip page draws, in one response: the trip, and every day of
 * its range with the places on it. Empty days are present — the page renders a
 * row for each one, and the client should not have to reconstruct the range.
 */
public record TripItinerary(
        @NotNull TripSummary trip,
        @NotNull List<TripDay> days) {
}
