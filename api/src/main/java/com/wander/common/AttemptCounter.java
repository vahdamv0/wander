package com.wander.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How many times something has happened under some key, lately.
 *
 * Extracted from {@code LoginThrottle}, which was the only counter here for as
 * long as guessing a password was the only thing worth counting. It is not any
 * more: the endpoints that spend this instance's Nominatim, Wikimedia and
 * Open-Meteo budget need a limit too, and a second hand-rolled map would have
 * been a second place to get the window arithmetic wrong.
 *
 * The shape is deliberately narrow. A key, a count, and the instant the run of
 * them began — a fixed window rather than a sliding one, because the point is to
 * make abuse expensive rather than to meter anything precisely, and a sliding
 * window costs a list per key instead of an int.
 *
 * **Bounded and access-ordered.** The keys are supplied by whoever is calling —
 * an email address, a client address — so an unbounded map is a way to fill the
 * heap with nonsense. The eviction is itself a limit worth naming: flood it with
 * enough distinct keys and somebody else's count is pushed out early. That is
 * the honest trade against the heap, and it is why the bound is a setting.
 *
 * **In memory, so per instance and lost on restart.** Fine while wander is one
 * container, and written down here for the day it is not.
 */
public final class AttemptCounter {

    private final Duration window;
    private final Map<String, Attempts> counts;

    /** How many, and when the run began. */
    private static final class Attempts {
        private int count;
        private Instant startedAt;

        Attempts(Instant now) {
            this.count = 1;
            this.startedAt = now;
        }
    }

    public AttemptCounter(Duration window, int trackedKeys) {
        this.window = window;
        int capacity = Math.max(1, trackedKeys);
        this.counts = Collections.synchronizedMap(new LinkedHashMap<String, Attempts>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Attempts> eldest) {
                return size() > capacity;
            }
        });
    }

    /** Whether this key has already reached {@code max} inside the window. */
    public boolean spent(String key, int max) {
        Instant now = Instant.now();
        synchronized (counts) {
            Attempts attempts = counts.get(key);
            if (attempts == null) {
                return false;
            }
            if (expired(attempts, now)) {
                // The window has passed; forget it rather than leaving a stale
                // count for the next caller to trip over.
                counts.remove(key);
                return false;
            }
            return attempts.count >= max;
        }
    }

    /** One more under this key. Starts a fresh window if the last one has passed. */
    public void record(String key) {
        Instant now = Instant.now();
        synchronized (counts) {
            Attempts attempts = counts.get(key);
            if (attempts == null || expired(attempts, now)) {
                counts.put(key, new Attempts(now));
                return;
            }
            attempts.count++;
        }
    }

    /** Forget this key entirely — what a success does to a run of failures. */
    public void forget(String key) {
        counts.remove(key);
    }

    /** For the tests that hold the bound on the map. */
    public int trackedKeyCount() {
        return counts.size();
    }

    private boolean expired(Attempts attempts, Instant now) {
        return attempts.startedAt.plus(window).isBefore(now);
    }
}
