package com.wander.route.dto;

import jakarta.validation.constraints.NotNull;

/** Travel estimate between consecutive located places in the planned order. */
public record RouteLegView(
        @NotNull Long fromPlaceId,
        @NotNull Long toPlaceId,
        @NotNull long seconds,
        @NotNull long metres) {
}
