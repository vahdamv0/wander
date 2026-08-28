package com.wander.weather;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.config.WanderProperties;
import com.wander.place.Place;
import com.wander.place.PlaceRepository;
import com.wander.trip.Trip;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripMember;
import com.wander.weather.WeatherClient.DailyForecast;
import com.wander.weather.dto.DayWeatherView;
import com.wander.weather.dto.TripWeather;

/**
 * The forecast for the days of a trip that there can be one for.
 *
 * Three things shape this service and none of them are the HTTP call:
 *
 * **A forecast has a horizon.** About sixteen days, and a trip is usually
 * planned further out than that. So a day outside the horizon produces no row and
 * no view — never a placeholder. A trip next July shows no weather at all until
 * July, and that is the honest rendering of "nobody knows yet".
 *
 * **A day's location comes from the itinerary**, as the first place on it that has
 * coordinates — and a day with nothing on it borrows from the **nearest planned
 * day**, before it by preference. Not the average of the trip's points: on a
 * Tokyo-then-Kyoto trip that average is a mountain range neither of them is near,
 * and it would cost a third outbound call to ask about it. An unplanned Tuesday is
 * almost always spent where Monday was. A trip with nothing located anywhere gets
 * no weather rather than a guess from its name.
 *
 * **An outage is not an error here.** Weather is decoration on a page whose job is
 * the itinerary, so a failed fetch is logged and the page renders with whatever is
 * already stored — possibly nothing. That is the opposite of the geocoder, where a
 * search that silently returned no results would look like "no such place".
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherClient client;
    private final DayWeatherRepository stored;
    private final PlaceRepository places;
    private final TripAccessService access;
    private final WanderProperties.Weather config;
    private final Duration ttl;

    public WeatherService(WeatherClient client, DayWeatherRepository stored,
            PlaceRepository places, TripAccessService access, WanderProperties properties) {
        this.client = client;
        this.stored = stored;
        this.places = places;
        this.access = access;
        this.config = properties.weather();
        this.ttl = Duration.ofMinutes(Math.max(15, config.cacheMinutes()));
    }

    @Transactional
    public TripWeather forTrip(Long userId, Long tripId) {
        TripMember member = access.requireMember(tripId, userId);
        if (!config.enabled()) {
            return TripWeather.unavailable();
        }
        Trip trip = member.getTrip();

        Map<LocalDate, double[]> wanted = pointsByDay(trip, tripId);
        if (wanted.isEmpty()) {
            // In range but nothing located, or the whole trip is beyond the
            // horizon. Available, with nothing to show — the client draws no
            // weather rather than an error.
            return new TripWeather(true, config.attribution(), config.attributionUrl(), List.of());
        }

        Map<LocalDate, DayWeather> rows = new HashMap<>();
        for (DayWeather row : stored.findByTripId(tripId)) {
            rows.put(row.getDayDate(), row);
        }

        fetchWhatIsMissing(trip, wanted, rows);

        List<DayWeatherView> views = new ArrayList<>(wanted.size());
        for (LocalDate date : wanted.keySet()) {
            DayWeather row = rows.get(date);
            // A row that is still stale here is one whose refetch just failed.
            // Shown anyway: yesterday's forecast for tomorrow beats a blank.
            if (row != null) {
                views.add(DayWeatherView.of(row, WeatherCodes.describe(row.getWeatherCode())));
            }
        }
        return new TripWeather(true, config.attribution(), config.attributionUrl(),
                List.copyOf(views));
    }

    /**
     * The days worth asking about, each with the point to ask about, in date
     * order.
     *
     * A day is worth asking about when it falls inside the forecast horizon and
     * the trip has somewhere to put it. Yesterday is excluded along with next
     * year: this is a forecast, and the past is not what it is for.
     */
    private Map<LocalDate, double[]> pointsByDay(Trip trip, Long tripId) {
        LocalDate today = LocalDate.now();
        LocalDate lastForecastDay = today.plusDays(config.horizonDays() - 1L);

        // The first located place of each day. Already ordered by rank, so "first"
        // is the one at the top of the day's list — which is where somebody
        // planning a day has put the thing that anchors it.
        Map<LocalDate, double[]> firstOfDay = new HashMap<>();
        for (Place place : places.findByTripIdOrderByDayDateAscSortOrderAsc(tripId)) {
            if (place.getLatitude() == null || place.getLongitude() == null) {
                continue;
            }
            firstOfDay.computeIfAbsent(place.getDayDate(),
                    date -> new double[] { place.getLatitude(), place.getLongitude() });
        }
        if (firstOfDay.isEmpty()) {
            return Map.of();
        }

        // Forward, carrying the last known location: an unplanned day is spent
        // where the day before it was.
        List<LocalDate> dates = new ArrayList<>(trip.dayCount());
        Map<LocalDate, double[]> resolved = new HashMap<>();
        double[] carried = null;
        for (int i = 0; i < trip.dayCount(); i++) {
            LocalDate date = trip.getStartDate().plusDays(i);
            dates.add(date);
            carried = firstOfDay.getOrDefault(date, carried);
            if (carried != null) {
                resolved.put(date, carried);
            }
        }
        // And once backwards, for the days before anything is planned at all —
        // a trip whose first located place is on day four still wants day one.
        carried = null;
        for (int i = dates.size() - 1; i >= 0; i--) {
            LocalDate date = dates.get(i);
            carried = resolved.getOrDefault(date, carried);
            if (carried != null) {
                resolved.put(date, carried);
            }
        }

        Map<LocalDate, double[]> wanted = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            // Yesterday is excluded along with next year: this is a forecast, and
            // the past is not what it is for.
            if (date.isBefore(today) || date.isAfter(lastForecastDay)) {
                continue;
            }
            wanted.put(date, resolved.get(date));
        }
        return wanted;
    }

    /**
     * Fetches the days whose stored row is missing, aged out, or was fetched for
     * somewhere else.
     *
     * One outbound call per distinct location, not per day: a forecast response
     * covers the whole horizon, so a trip that stays in one city costs one call
     * however long it is, and a Tokyo-then-Kyoto trip costs two.
     */
    private void fetchWhatIsMissing(Trip trip, Map<LocalDate, double[]> wanted,
            Map<LocalDate, DayWeather> rows) {
        Map<String, List<LocalDate>> byLocation = new LinkedHashMap<>();
        wanted.forEach((date, point) -> {
            DayWeather row = rows.get(date);
            if (row == null || !row.isFreshFor(point[0], point[1], ttl)) {
                byLocation.computeIfAbsent(key(point), key -> new ArrayList<>()).add(date);
            }
        });

        for (Map.Entry<String, List<LocalDate>> group : byLocation.entrySet()) {
            List<LocalDate> dates = group.getValue();
            double[] point = wanted.get(dates.get(0));

            List<DailyForecast> forecasts;
            try {
                forecasts = client.fetch(point[0], point[1], config.horizonDays());
            } catch (RuntimeException ex) {
                // Logged, not thrown. See the class comment: the itinerary is the
                // page, and it must render.
                log.warn("Weather fetch failed for trip {}: {}", trip.getId(), ex.getMessage());
                continue;
            }

            Map<LocalDate, DailyForecast> byDate = new HashMap<>();
            for (DailyForecast forecast : forecasts) {
                byDate.put(forecast.date(), forecast);
            }

            for (LocalDate date : dates) {
                DailyForecast forecast = byDate.get(date);
                // Absent means the upstream's own horizon is shorter than ours
                // asked for. Nothing is written, so nothing is shown.
                if (forecast == null) {
                    continue;
                }
                DayWeather row = Optional.ofNullable(rows.get(date))
                        .orElseGet(() -> stored.save(new DayWeather(trip, date)));
                row.replaceWith(point[0], point[1], forecast);
                rows.put(date, row);
            }
        }
    }

    /**
     * Groups locations that are close enough to share a forecast — the same
     * tolerance {@link DayWeather#isFreshFor} uses, or a row would be written and
     * then immediately judged stale.
     */
    private static String key(double[] point) {
        return Math.round(point[0] * 100) + "," + Math.round(point[1] * 100);
    }
}
