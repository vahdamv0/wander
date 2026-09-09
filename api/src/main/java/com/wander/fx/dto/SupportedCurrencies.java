package com.wander.fx.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * What the currency picker may offer.
 *
 * It comes from the server rather than a list compiled into the Angular app for
 * the reason the tile URL does: coverage belongs to whatever upstream this
 * instance points at, it moves, and a hardcoded table would be wrong on somebody
 * else's instance with nothing to say so.
 *
 * {@code lookupEnabled} is the honest half. When it is false the list is empty
 * and the client offers the browser's own ISO list instead, with the rate typed
 * by hand — which is the only thing that can work on an instance with no
 * outbound network, and is a supported way to run this rather than a degraded
 * one.
 */
public record SupportedCurrencies(
        @NotNull boolean lookupEnabled,
        /** ISO 4217 codes, sorted, or empty when nothing can be looked up. */
        @NotNull List<String> currencies,
        /** Shown beside a converted amount, so the number says where it came from. */
        @NotNull String attribution,
        @NotNull String attributionUrl) {
}
