package com.wander.geo;

import java.util.concurrent.TimeUnit;

import com.wander.common.RateLimitedException;

/**
 * Lets one caller through per interval, application-wide.
 *
 * Nominatim's usage policy caps a client at one request a second, and it is the
 * whole instance that gets blocked for breaking it, not one user — so the limit
 * has to be shared rather than per session.
 *
 * A caller that arrives early waits, because a search that takes an extra
 * 300ms is better than one that fails. A caller that would have to wait longer
 * than {@code maxWait} gets 429 instead, so a burst cannot pile a queue of
 * request threads up behind the gate.
 *
 * Blocking a thread here is cheap: virtual threads are on, so a parked request
 * is not holding a platform thread.
 */
class RateGate {

    private final long minIntervalNanos;
    private final long maxWaitNanos;

    /** Guarded by {@code this}: the moment the last permitted call went out. */
    private long lastPassNanos = Long.MIN_VALUE;

    RateGate(long minIntervalMillis, long maxWaitMillis) {
        this.minIntervalNanos = TimeUnit.MILLISECONDS.toNanos(minIntervalMillis);
        this.maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitMillis);
    }

    /**
     * Returns once the caller may proceed, having reserved its slot.
     *
     * @throws RateLimitedException if the wait would be longer than the maximum
     */
    void pass() {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            // Nanos are monotonic but can be negative, so compare a difference
            // rather than the values themselves.
            long earliest = lastPassNanos == Long.MIN_VALUE ? now : lastPassNanos + minIntervalNanos;
            waitNanos = Math.max(0, earliest - now);
            if (waitNanos > maxWaitNanos) {
                throw new RateLimitedException("Too many place searches at once — try again shortly");
            }
            // Reserved before the sleep, so concurrent callers space out instead
            // of all waking to the same slot.
            lastPassNanos = now + waitNanos;
        }
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RateLimitedException("Place search was interrupted");
            }
        }
    }
}
