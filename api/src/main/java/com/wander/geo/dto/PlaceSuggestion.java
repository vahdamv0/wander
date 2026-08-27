package com.wander.geo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * One geocoder hit, reduced to what the planner needs. Deliberately not a
 * pass-through of Nominatim's payload: its shape is not this project's contract,
 * and a self-hoster pointing at a different geocoder should not change the API.
 */
public record PlaceSuggestion(
        /** The upstream's own reference, e.g. {@code node/240109189}. Stable enough to key a list on. */
        @NotNull String ref,
        /** Short label — "Sagrada Família". */
        @NotNull String name,
        /** The full formatted line the geocoder returned. */
        @NotNull String address,
        @NotNull Double latitude,
        @NotNull Double longitude,
        /** What kind of thing this is ("restaurant", "attraction"), when the upstream says. */
        String category) {
}
