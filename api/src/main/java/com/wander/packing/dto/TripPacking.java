package com.wander.packing.dto;

import java.util.List;

import com.wander.trip.dto.TripSummary;

import jakarta.validation.constraints.NotNull;

/**
 * The packing page in one request, the same shape as `TripItinerary` and
 * `TripExpenses`. The trip comes along for its name and the caller's role.
 */
public record TripPacking(
        @NotNull TripSummary trip,
        @NotNull List<PackingGroup> groups,
        @NotNull int totalCount,
        @NotNull int packedCount) {
}
