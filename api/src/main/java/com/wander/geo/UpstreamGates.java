package com.wander.geo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wander.config.WanderProperties;

/**
 * The rate limits this instance owes its upstreams.
 *
 * One gate per *service*, not per feature. Nominatim's policy is one request a
 * second from a client, and it is the instance that gets blocked for breaking it,
 * so place search and enrichment's tag lookup queue behind the same object. This
 * class exists so that sharing is a wiring fact rather than something each new
 * caller has to remember.
 */
@Configuration
public class UpstreamGates {

    /** Shared by {@code GeocodingService} and enrichment's OSM lookup. */
    @Bean
    public RateGate nominatimGate(WanderProperties properties) {
        WanderProperties.Geocoding config = properties.geocoding();
        return new RateGate(config.minIntervalMillis(), config.maxWaitMillis());
    }

    /**
     * Wikimedia's APIs — Wikidata, Wikipedia, Commons.
     *
     * A separate gate because they are separate services with their own, far more
     * generous limits; sharing Nominatim's would make an enrichment wait behind
     * somebody's typeahead for no reason. Still gated rather than unlimited: the
     * etiquette is a sane request rate and an identifying User-Agent, and a bug
     * that fetches in a loop should be slow rather than abusive.
     */
    @Bean
    public RateGate wikimediaGate(WanderProperties properties) {
        WanderProperties.Enrichment config = properties.enrichment();
        return new RateGate(config.minIntervalMillis(), config.maxWaitMillis());
    }
}
