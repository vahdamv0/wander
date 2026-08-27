package com.wander.geo;

import java.util.List;

import com.wander.geo.dto.PlaceSuggestion;

/**
 * The one call this project makes to a geocoder. An interface because it is the
 * seam the tests replace — the alternative is a suite that only passes with a
 * network connection and hammers a free public service while it runs.
 */
public interface GeocoderClient {

    /**
     * Forward geocoding: free text in, ranked candidates out. Never null.
     *
     * @param language a browser-style language list ("en-GB,en;q=0.9"). Without
     *                 one a geocoder answers in whatever the place's own
     *                 language is, so a search for Kyoto comes back in Japanese.
     */
    List<PlaceSuggestion> search(String query, int limit, String language);
}
