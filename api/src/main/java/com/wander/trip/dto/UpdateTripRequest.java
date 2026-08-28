package com.wander.trip.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The whole trip, rewritten. Same fields as creating one, because a trip is
 * small enough that a partial update would only be a way to forget one.
 *
 * Moving the dates is the interesting part, and the rule lives in
 * `TripService.update`: a move that keeps the same length carries the itinerary
 * with it, and one that would leave places or notes outside the new range is
 * refused rather than hiding or deleting them.
 */
public record UpdateTripRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 120) String destination,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        /**
         * Changeable only while the trip has no expenses. After that the stored
         * amounts mean something in the old currency, so switching would be a
         * re-denomination rather than a relabel — and this endpoint refuses it.
         * Omitted leaves it alone.
         */
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO 4217 code")
        String currency,
        /**
         * Move the itinerary along with the start date, even though the trip's
         * length is changing.
         *
         * A same-length move always shifts, because there is no other sensible
         * reading of it. A length change is ambiguous — "we added two days at the
         * front" wants the existing plan to keep its calendar dates, while "we
         * moved it a month later and made it longer" wants the plan to come along
         * — and the server cannot tell which is meant. So it refuses by default
         * and the client asks.
         */
        Boolean shiftItinerary) {
}
