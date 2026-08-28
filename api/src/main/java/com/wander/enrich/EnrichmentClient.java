package com.wander.enrich;

/**
 * The seam the tests replace.
 *
 * One interface for four upstreams on purpose, exactly as `GeocoderClient` is one
 * for the geocoder. The chain — OpenStreetMap tags, then Wikidata, then Wikipedia,
 * then Commons — is an implementation detail of *how* a place is looked up, not
 * something the service above should orchestrate or the tests should have to mock
 * four times over. It is also what keeps the suite off four public APIs and their
 * rate budgets.
 */
public interface EnrichmentClient {

    /**
     * Everything findable about a place, or {@link PlaceFacts#empty()} when the
     * upstreams have nothing — which is the common case and not an error.
     *
     * @param osmRef   the geocoder's reference, e.g. {@code way/34633854}
     * @param language the caller's preferred Wikipedia, e.g. {@code en}
     */
    PlaceFacts fetch(String osmRef, String language);
}
