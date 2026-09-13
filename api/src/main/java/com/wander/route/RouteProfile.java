package com.wander.route;

/**
 * How you are getting between the stops.
 *
 * The lowercase name is the profile segment in an OSRM URL
 * ({@code /table/v1/walking/…}), which is why these three and not, say,
 * "transit": OSRM routes on a road graph and knows nothing about timetables.
 * A day is walked, driven or cycled, and the answer is a genuinely different
 * order for each — walking two streets is nothing and driving the same two
 * streets against a one-way system is ten minutes.
 *
 * A self-hosted osrm-backend ignores the segment entirely (it serves whatever
 * graph it was built with), so an operator running one profile gets the same
 * answer for all three. That is their configuration to make, not something to
 * hide by offering fewer choices.
 */
public enum RouteProfile {

    DRIVING,
    WALKING,
    CYCLING;

    /** The path segment OSRM expects. */
    public String segment() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
