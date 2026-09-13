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

    /**
     * Open-Meteo. Its own gate again, and its own budget: the free tier is
     * "less than 10'000 API calls per day" for the whole instance, which is
     * plenty for day cards that are cached in hours and stingy for a bug that
     * fetches in a loop. This is what makes the second case slow rather than a
     * reason to be blocked.
     */
    @Bean
    public RateGate openMeteoGate(WanderProperties properties) {
        WanderProperties.Weather config = properties.weather();
        return new RateGate(config.minIntervalMillis(), config.maxWaitMillis());
    }

    /**
     * OSRM, for auto-sorting a day. Its own gate, and the one most likely to be
     * pointed at a machine in the next rack — {@code wander.routing.base-url}
     * defaults to localhost, because there is no public routing service anybody
     * is entitled to build on. Gated anyway: a matrix is the most expensive
     * question this application asks of anything, and a self-hosted engine is
     * still somebody's CPU.
     */
    @Bean
    public RateGate osrmGate(WanderProperties properties) {
        WanderProperties.Routing config = properties.routing();
        return new RateGate(config.minIntervalMillis(), config.maxWaitMillis());
    }

    /**
     * Frankfurter, for exchange rates. Its own gate again.
     *
     * The least-used of the four by a wide margin, and deliberately still gated.
     * Its cache never expires — a rate published for a past day is final, not
     * merely fresh — so a healthy instance reaches this upstream a handful of
     * times a day, and the gate is here for the unhealthy one: a bug that asks
     * in a loop should be slow rather than be the reason a free service starts
     * refusing this instance.
     */
    @Bean
    public RateGate frankfurterGate(WanderProperties properties) {
        WanderProperties.Fx config = properties.fx();
        return new RateGate(config.minIntervalMillis(), config.maxWaitMillis());
    }
}
