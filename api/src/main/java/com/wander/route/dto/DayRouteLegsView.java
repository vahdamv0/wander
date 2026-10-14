package com.wander.route.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotNull;

/** Routed estimates for one day's consecutive located places, in planned order. */
public record DayRouteLegsView(
        @NotNull LocalDate dayDate,
        @NotNull String profile,
        @NotNull List<RouteLegView> legs,
        @NotNull String attribution,
        @NotNull String attributionUrl) {
}
