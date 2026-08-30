package com.wander.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.wander.common.RateLimitedException;
import com.wander.config.WanderProperties;

/**
 * How many times a password may be guessed before this instance stops listening.
 *
 * `/api/auth/login` is the one endpoint on an internet-facing instance that gets
 * probed without anybody choosing to attack it in particular, and bcrypt alone is
 * not an answer: it makes each guess expensive for the *server*, which is a
 * denial of service as much as a defence. Nothing else here needed this —
 * `RateGate` throttles what wander sends *out*, to stay inside somebody else's
 * budget, and is a queue rather than a refusal.
 *
 * Two counters, and both matter because they fail differently:
 *
 *  - **by email**, so one account cannot be ground through a word list from a
 *    thousand addresses;
 *  - **by client address**, so a spray across many accounts from one place is
 *    stopped even though no single account ever reaches its own limit. Its limit
 *    is the looser of the two, because a household, an office or a phone network
 *    is one address to us and several people signing in.
 *
 * A *successful* sign-in clears both, so the only thing that accumulates is
 * failure. That is what keeps this off the path of anybody using the application
 * normally: fat-fingering a password twice and then getting it right leaves no
 * trace at all.
 *
 * **In memory, and per instance.** Two things follow. It is lost on restart —
 * acceptable, because the window is minutes and a restart is not something an
 * attacker can ask for — and it counts nothing that a second instance saw, which
 * is fine while wander is one container and is written down here for the day it
 * is not. The map is bounded and access-ordered for the same reason the geocoder
 * cache is: the keys are attacker-supplied, so an unbounded one is a way to fill
 * the heap by sending nonsense addresses.
 *
 * What this deliberately does not do is lock an account. A lockout that outlives
 * the window turns a guessing attempt into a way of keeping the real owner out —
 * and with no password reset on this instance (see README), being locked out is
 * not recoverable without the operator.
 */
@Component
public class LoginThrottle {

    private final int maxPerEmail;
    private final int maxPerAddress;
    private final Duration window;
    private final Map<String, Attempts> failures;

    /** Failures so far, and when the run of them began. */
    private static final class Attempts {
        private int count;
        private Instant startedAt;

        Attempts(Instant now) {
            this.count = 1;
            this.startedAt = now;
        }
    }

    // Explicit, because the second constructor below means there is a choice to
    // make and Spring will not guess between two.
    @Autowired
    public LoginThrottle(WanderProperties properties) {
        this(properties.login().maxFailuresPerEmail(), properties.login().maxFailuresPerAddress(),
                Duration.ofMinutes(properties.login().windowMinutes()), properties.login().trackedKeys());
    }

    /** Takes the window as a Duration so a test can use one measured in milliseconds. */
    LoginThrottle(int maxPerEmail, int maxPerAddress, Duration window, int trackedKeys) {
        this.maxPerEmail = maxPerEmail;
        this.maxPerAddress = maxPerAddress;
        this.window = window;
        int capacity = Math.max(1, trackedKeys);
        this.failures = Collections.synchronizedMap(new LinkedHashMap<String, Attempts>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Attempts> eldest) {
                return size() > capacity;
            }
        });
    }

    /**
     * Called before the password is checked at all — the point is to not spend
     * the hash.
     *
     * @throws RateLimitedException when either counter is spent
     */
    public void check(String email, String address) {
        Instant now = Instant.now();
        if (spent(emailKey(email), maxPerEmail, now) || spent(addressKey(address), maxPerAddress, now)) {
            // Deliberately the same message either way, and the same one an
            // unknown address gets: which counter ran out is a fact about who has
            // an account here.
            throw new RateLimitedException("Too many sign-in attempts. Wait a few minutes and try again.");
        }
    }

    /** A wrong password. Counts against the account and against where it came from. */
    public void failed(String email, String address) {
        Instant now = Instant.now();
        record(emailKey(email), now);
        record(addressKey(address), now);
    }

    /** The right password. Both counters go, so a normal day never accumulates. */
    public void succeeded(String email, String address) {
        failures.remove(emailKey(email));
        failures.remove(addressKey(address));
    }

    private boolean spent(String key, int max, Instant now) {
        synchronized (failures) {
            Attempts attempts = failures.get(key);
            if (attempts == null) {
                return false;
            }
            if (expired(attempts, now)) {
                // The window has passed; forget it rather than leaving a stale
                // count for the next caller to trip over.
                failures.remove(key);
                return false;
            }
            return attempts.count >= max;
        }
    }

    private void record(String key, Instant now) {
        synchronized (failures) {
            Attempts attempts = failures.get(key);
            if (attempts == null || expired(attempts, now)) {
                failures.put(key, new Attempts(now));
                return;
            }
            attempts.count++;
        }
    }

    /** For the test that holds the bound on the map. */
    int trackedKeyCount() {
        return failures.size();
    }

    private boolean expired(Attempts attempts, Instant now) {
        return attempts.startedAt.plus(window).isBefore(now);
    }

    /**
     * Lowercased, because the login itself is case-insensitive on email — without
     * this, alternating the capitals is a way to start again with a fresh count.
     */
    private static String emailKey(String email) {
        return "e:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "a:" + (address == null ? "" : address);
    }
}
