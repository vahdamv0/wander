package com.wander.route;

import java.util.List;

/**
 * The seam the tests replace, exactly as {@code GeocoderClient},
 * {@code EnrichmentClient}, {@code WeatherClient}, {@code MailClient} and
 * {@code FxRateClient} are for theirs. Sixth of its kind, and shaped the same
 * way for the same reason: it is what keeps the suite off the network and off a
 * donated service's budget.
 *
 * Deliberately a **matrix**, not a route. The optimiser needs the cost between
 * every pair of stops to decide an order, which is one call for a whole day
 * rather than n² of them — and the shape of the path between two of them is
 * something the person will get from their phone, not from here.
 */
public interface RouteClient {

    /**
     * Travel time and distance between every pair of the given points, in the
     * order they were given.
     *
     * @throws com.wander.common.UpstreamUnavailableException when the engine
     *         cannot answer, or answers with a hole in it — a missing leg must
     *         never reach the optimiser as a zero cost, which would make the
     *         unreachable stop look like the nearest one.
     */
    Matrix table(RouteProfile profile, List<Point> points);

    /** A place to route through: longitude and latitude, in that order upstream. */
    record Point(double latitude, double longitude) {
    }

    /**
     * Square matrices indexed by the order the points were given in.
     * {@code seconds[i][j]} is the time from stop i to stop j; distances ride
     * along because they are free in the same response and are what the client
     * shows when a profile has no useful clock.
     */
    record Matrix(long[][] seconds, long[][] metres) {

        public int size() {
            return seconds.length;
        }
    }
}
