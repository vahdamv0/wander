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

        @DefaultValue Admin admin,

        @DefaultValue Geocoding geocoding) {

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
            /** Minimum gap between two outbound searches, in milliseconds. */
            @DefaultValue("1000") long minIntervalMillis,
            /** How long a caller waits for the gate before getting a 429 instead. */
            @DefaultValue("2000") long maxWaitMillis,
            /** How long a result stays cached. */
            @DefaultValue("600") long cacheSeconds,
            /** How many distinct queries to remember. */
            @DefaultValue("500") int cacheSize) {
    }
}
