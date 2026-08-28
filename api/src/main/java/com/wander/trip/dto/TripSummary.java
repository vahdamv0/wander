package com.wander.trip.dto;

import java.time.LocalDate;

import com.wander.trip.Trip;
import com.wander.trip.TripRole;

import jakarta.validation.constraints.NotNull;

/** List-shaped view. `myRole` saves the client a second call to render controls. */
public record TripSummary(
        // @NotNull on a response DTO is documentation that springdoc turns into
        // `required` in the schema — without it every generated TypeScript field
        // is optional and the client fills up with non-null assertions.
        @NotNull Long id,
        @NotNull String name,
        /** Nullable on purpose: a trip need not have one. */
        String destination,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull int dayCount,
        /** ISO 4217. Travels with every trip so the client can format money anywhere one appears. */
        @NotNull String currency,
        @NotNull TripRole myRole) {

    public static TripSummary of(Trip trip, TripRole myRole) {
        return new TripSummary(trip.getId(), trip.getName(), trip.getDestination(), trip.getStartDate(),
                trip.getEndDate(), trip.dayCount(), trip.getCurrency(), myRole);
    }
}
