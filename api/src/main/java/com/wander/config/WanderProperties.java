package com.wander.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instance configuration. Every field is env-var settable so an operator can run
 * the container without editing a file — see compose.yaml.
 */
@ConfigurationProperties("wander")
public record WanderProperties(

        /** Reported in the geocoder User-Agent, which is how a blocked instance gets identified. */
        @DefaultValue("dev") String version,

        /** Self-signup. Off means an admin creates accounts (invites land in a later milestone). */
        @DefaultValue("true") boolean registrationEnabled,

        /**
         * The currency a new trip gets when its creator does not choose one. An
         * operator setting rather than a compiled-in constant for the same reason
         * the tile URL is: this project has no idea where its instances are.
         */
        @DefaultValue("EUR") String currency,

        @DefaultValue Admin admin,

        @DefaultValue Geocoding geocoding,

        @DefaultValue Enrichment enrichment,

        @DefaultValue MapTiles map) {

    /**
     * First-boot admin. Both blank means the account is still created, with a
     * generated password printed once to the log — the operator never has to
     * hand-edit the database to get in.
     */
    public record Admin(@DefaultValue("") String email, @DefaultValue("") String password) {
    }

    /**
     * Place search. Defaults point at the public Nominatim instance, whose usage
     * policy this project honours: an identifying User-Agent, at most one
     * request a second across the whole instance, and results cached so the same
     * query is not asked twice.
     *
     * An operator with real traffic should point {@code baseUrl} at their own
     * Nominatim and raise the rate — or set {@code enabled: false}, which is the
     * right setting for an instance with no outbound network access.
     */
    public record Geocoding(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://nominatim.openstreetmap.org") String baseUrl,
            /** Sent in the User-Agent so the operator is contactable before being blocked. */
            @DefaultValue("") String contactEmail,
            /**
             * Fallback for callers that send no Accept-Language. Not empty by
             * design: a geocoder with no language preference answers in the
             * place's own language, and this application's own UI is English.
             */
            @DefaultValue("en") String language,
            /** Minimum gap between two outbound searches, in milliseconds. */
            @DefaultValue("1000") long minIntervalMillis,
            /** How long a caller waits for the gate before getting a 429 instead. */
            @DefaultValue("2000") long maxWaitMillis,
            /** How long a result stays cached. */
            @DefaultValue("600") long cacheSeconds,
            /** How many distinct queries to remember. */
            @DefaultValue("500") int cacheSize) {
    }

    /**
     * Descriptions, facts, hours and photos for a place, from OpenStreetMap,
     * Wikidata, Wikipedia and Wikimedia Commons.
     *
     * Off means the popup shows nothing but the place itself, which is also what
     * an instance with no outbound network gets. Everything fetched is stored, so
     * the rate here is about first sight of a place rather than steady traffic.
     */
    public record Enrichment(
            @DefaultValue("true") boolean enabled,
            /** Wikipedia and Commons hosts. Language is chosen per request from the caller's. */
            @DefaultValue("https://www.wikidata.org") String wikidataUrl,
            @DefaultValue("https://commons.wikimedia.org") String commonsUrl,
            /** How long a stored enrichment stands before it is fetched again. */
            @DefaultValue("30") int cacheDays,
            /** How many photo candidates to offer. More is a longer strip nobody scrolls. */
            @DefaultValue("4") int photoCount,
            /** Gap between outbound Wikimedia calls, and how long a caller waits for the gate. */
            @DefaultValue("200") long minIntervalMillis,
            @DefaultValue("4000") long maxWaitMillis) {
    }

    /**
     * The tile layer the browser draws the map from. Not compiled into the
     * client: an operator running their own tile server, or one who would rather
     * their users' browsers not talk to openstreetmap.org at all, changes this
     * and every client follows — which is also why the attribution travels with
     * the URL rather than being hardcoded next to the map.
     *
     * The default is the OpenStreetMap tile service, whose policy requires the
     * attribution below to stay visible and asks that heavy users run their own.
     */
    public record MapTiles(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://tile.openstreetmap.org/{z}/{x}/{y}.png") String tileUrl,
            @DefaultValue("© OpenStreetMap contributors") String attribution,
            @DefaultValue("19") int maxZoom) {
    }
}
