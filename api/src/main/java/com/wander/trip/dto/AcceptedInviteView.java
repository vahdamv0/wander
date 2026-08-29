package com.wander.trip.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Where to go now that you are on the trip.
 *
 * A record rather than the bare id: springdoc renders a `Map<String, Long>` as a
 * free-form object, and the generated client would hand back an index signature
 * instead of a named type.
 */
public record AcceptedInviteView(@NotNull Long tripId) {
}
