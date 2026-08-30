package com.wander.config.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Map settings, passed through to the client as they are.
 *
 * Two sources, and {@code styleUrl} wins whenever it is not blank. A vector
 * style lets the client choose the label language, because the names arrive as
 * data rather than baked into a picture; a raster {@code tileUrl} cannot, which
 * is why the labels on a Japanese trip used to be Japanese whatever the browser
 * asked for.
 *
 * Both are sent every time rather than one being resolved server-side: the
 * choice depends on the reader's theme, which is a browser preference the server
 * has no business knowing.
 */
public record MapConfig(
        /** False hides the map entirely; the itinerary is unaffected. */
        @NotNull boolean enabled,
        /** A MapLibre style document URL. Blank means "use the raster tiles instead". */
        @NotNull String styleUrl,
        /** The style used while the reader's theme is dark. Blank falls back to styleUrl. */
        @NotNull String darkStyleUrl,
        /** A Leaflet URL template, {@code {z}/{x}/{y}}. Used only when styleUrl is blank. */
        @NotNull String tileUrl,
        /** Must stay visible on the map — every tile service's terms require it. */
        @NotNull String attribution,
        @NotNull int maxZoom) {
}
