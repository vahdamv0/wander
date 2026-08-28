package com.wander.weather;

import java.time.LocalDate;
import java.util.List;

/**
 * The seam the tests replace, exactly as {@code GeocoderClient} and
 * {@code EnrichmentClient} are for their upstreams.
 *
 * Deliberately shaped as "the forecast from today, for one point" rather than
 * "the forecast for these dates": that is what a forecast API can actually
 * answer, and pretending otherwise would push the horizon problem into the client
 * implementation where each upstream would solve it differently. The service
 * above picks the days it wanted out of the answer.
 */
public interface WeatherClient {

    /**
     * Daily highs, lows and conditions for {@code days} days beginning today, at
     * the given point — or an empty list when the upstream has nothing.
     *
     * Dates are local to the coordinates, not to this server: "day three" means
     * the day it is where the trip is.
     */
    List<DailyForecast> fetch(double latitude, double longitude, int days);

    /** One day, in Celsius and a WMO weather code. */
    record DailyForecast(LocalDate date, double tempMaxC, double tempMinC, int weatherCode) {
    }
}
