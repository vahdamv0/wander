package com.wander.common;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.wander.config.WanderProperties;

/**
 * How much of this instance's upstream budget one signed-in person may spend.
 *
 * Five endpoints reach somebody else's service on a caller's behalf — place
 * search, place enrichment, the forecast, an exchange rate and sorting a day by
 * route — and every one of them runs on capacity that belongs to the *instance*
 * rather than to a user; donated, in four cases, or the operator's own machine
 * in the routing engine's. `.env.example` names that as the reason self-signup is off by default: an
 * open sign-up form on a public hostname hands Nominatim, Wikimedia, Open-Meteo
 * and Frankfurter to whoever finds the address. This is the counter that makes
 * opening it survivable.
 *
 * **`RateGate` is not this, and does not help here.** It spaces what wander sends
 * *out*, application-wide, by parking the request thread until a slot is free.
 * With no inbound limit that makes one heavy caller everybody else's problem
 * rather than their own: every other user's search queues behind the same gate,
 * and past its maximum wait the 429s land on people who did nothing. A limit per
 * caller is what turns a shared outage back into one person being told to slow
 * down.
 *
 * **Keyed by user id, not by address.** All three endpoints are authenticated, so
 * there is a better key available than the one that arrives in a header — the
 * user id cannot be rotated, cannot be forged, and needs no proxy to be
 * configured correctly to mean anything. The per-address counters on the auth
 * endpoints have no such luxury, which is what the Caddyfile's `header_up` line
 * is for.
 *
 * Cached reads count too. The cache spares the *upstream*, which is why the
 * limits are generous, but a caller hammering a cached query still occupies a
 * request thread and still queues on the gate — and a quota that only counted
 * misses would be a quota anybody could sit just underneath.
 */
@Component
public class UpstreamQuota {

    private final AttemptCounter counter;
    private final int searches;
    private final int enrichments;
    private final int forecasts;
    private final int rates;
    private final int routes;

    public UpstreamQuota(WanderProperties properties) {
        WanderProperties.Quota quota = properties.quota();
        this.counter = new AttemptCounter(Duration.ofMinutes(quota.windowMinutes()), quota.trackedKeys());
        this.searches = quota.searchesPerWindow();
        this.enrichments = quota.enrichmentsPerWindow();
        this.forecasts = quota.forecastsPerWindow();
        this.rates = quota.ratesPerWindow();
        this.routes = quota.routesPerWindow();
    }

    /** A place search. The loosest of the four — it is a typeahead. */
    public void search(Long userId) {
        spend("q:" + userId, searches, "Too many place searches. Wait a few minutes and try again.");
    }

    /** Opening a place's panel, which may walk four services on first sight. */
    public void enrichment(Long userId) {
        spend("e:" + userId, enrichments, "Too many place lookups. Wait a few minutes and try again.");
    }

    /** A trip's forecast. One request covers every day, so this is per page view. */
    public void forecast(Long userId) {
        spend("w:" + userId, forecasts, "Too many forecast requests. Wait a few minutes and try again.");
    }

    /**
     * An exchange rate, for the expense form's preview.
     *
     * The tightest of the four, and it can afford to be: a rate is asked for
     * once per foreign expense somebody is actually typing, and the cache behind
     * it never expires, so the number that matters is how fast a person fills in
     * a form rather than how many expenses a trip has.
     */
    public void rate(Long userId) {
        spend("x:" + userId, rates, "Too many exchange rate lookups. Wait a few minutes and try again.");
    }

    /**
     * Auto-sorting one day.
     *
     * One click is one matrix, which is the heaviest single question this
     * application asks anything, and the answer cannot be cached — the key
     * would be an ordered set of coordinates that changes the moment a place
     * moves. So the counter is the only thing standing between a stuck button
     * and a routing engine.
     */
    public void route(Long userId) {
        spend("o:" + userId, routes, "Too many route lookups. Wait a few minutes and try again.");
    }

    /**
     * Checked before the work, recorded after the check — so a caller who is
     * already over the line costs a map lookup rather than an outbound call.
     */
    private void spend(String key, int max, String message) {
        if (counter.spent(key, max)) {
            throw new RateLimitedException(message);
        }
        counter.record(key);
    }
}
