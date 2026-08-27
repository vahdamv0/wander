package com.wander.config.dto;

import jakarta.validation.constraints.NotNull;

/** Tile layer settings, passed through to Leaflet as they are. */
public record MapConfig(
        /** False hides the map entirely; the itinerary is unaffected. */
        @NotNull boolean enabled,
        /** A Leaflet URL template, {@code {z}/{x}/{y}}. */
        @NotNull String tileUrl,
        /** Must stay visible on the map — the tile service's terms require it. */
        @NotNull String attribution,
        @NotNull int maxZoom) {
}
