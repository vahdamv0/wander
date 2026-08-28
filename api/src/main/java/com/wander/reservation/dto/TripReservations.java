package com.wander.reservation.dto;

import java.util.List;

import com.wander.trip.dto.TripSummary;

import jakarta.validation.constraints.NotNull;

/** The page in one request, the same shape as the itinerary, ledger and packing list. */
public record TripReservations(
        @NotNull TripSummary trip,
        @NotNull List<ReservationView> reservations) {
}
