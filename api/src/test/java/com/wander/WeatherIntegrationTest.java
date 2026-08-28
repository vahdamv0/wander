package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.weather.WeatherClient;
import com.wander.weather.WeatherClient.DailyForecast;

/**
 * Weather over HTTP, with the forecast service replaced.
 *
 * The mock is not here to keep the suite fast — it is here because everything
 * worth testing about this feature is a decision made *around* the upstream, and
 * every one of those decisions is invisible if a real forecast is answering:
 * which days are asked about at all, how few calls a long trip costs, and what
 * happens when the service is down.
 *
 * Dates are relative to today rather than fixed. A forecast has a horizon, so a
 * test written against 2027-03-28 would pass until 2027 and then quietly start
 * testing the horizon instead of the caching.
 */
class WeatherIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private WeatherClient weatherClient;

    /** Kyoto, roughly. */
    private static final double LAT = 35.0;
    private static final double LON = 135.75;

    /** Kanazawa — far enough away to be a different forecast. */
    private static final double FAR_LAT = 36.56;
    private static final double FAR_LON = 136.65;

    private static String iso(int daysFromToday) {
        return LocalDate.now().plusDays(daysFromToday).toString();
    }

    /** A plausible answer: the full horizon from today, warming up as it goes. */
    private static List<DailyForecast> horizonFrom(int days) {
        List<DailyForecast> forecasts = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            forecasts.add(new DailyForecast(LocalDate.now().plusDays(i), 18.0 + i, 7.0 + i, 3));
        }
        return forecasts;
    }

    private Object tripFrom(Session owner, int firstDay, int lastDay) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Weather test","startDate":"%s","endDate":"%s"}
                """.formatted(iso(firstDay), iso(lastDay))).getBody()).get("id");
    }

    private void addPlace(Session owner, Object tripId, String day, String name,
            double latitude, double longitude) {
        assertThat(post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"%s","latitude":%s,"longitude":%s}
                """.formatted(day, name, latitude, longitude)).getStatusCode().value())
                .isEqualTo(201);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> daysOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("days");
    }

    /** A distinctly colder answer, so a refetch is visible in the temperature. */
    private static List<DailyForecast> coldHorizonFrom(int days) {
        List<DailyForecast> forecasts = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            forecasts.add(new DailyForecast(LocalDate.now().plusDays(i), 1.0 + i, -6.0 + i, 71));
        }
        return forecasts;
    }

    @SuppressWarnings("unchecked")
    private Object firstPlaceIdOn(Session caller, Object tripId, String day) {
        List<Map<String, Object>> days =
                (List<Map<String, Object>>) asMap(
                        get(caller, "/api/trips/" + tripId + "/itinerary").getBody()).get("days");
        return days.stream()
                .filter(d -> day.equals(d.get("date")))
                .flatMap(d -> ((List<Map<String, Object>>) d.get("places")).stream())
                .findFirst().orElseThrow().get("id");
    }

    private Map<String, Object> weatherFor(Session caller, Object tripId) {
        var response = get(caller, "/api/trips/" + tripId + "/weather");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return asMap(response.getBody());
    }

    @Test
    void aForecastIsFetchedOnceAndThenServedFromTheDatabase() {
        when(weatherClient.fetch(anyDouble(), anyDouble(), anyInt())).thenReturn(horizonFrom(16));

        Session alice = register("alice");
        Object tripId = tripFrom(alice, 1, 3);
        addPlace(alice, tripId, iso(1), "Fushimi Inari", LAT, LON);

        Map<String, Object> first = weatherFor(alice, tripId);
        assertThat(first).containsEntry("available", true);
        assertThat((String) first.get("attribution"))
                .as("CC BY 4.0 obliges the credit, and it travels with the data")
                .contains("Open-Meteo");
        assertThat(daysOf(first)).hasSize(3);

        Map<String, Object> day = daysOf(first).get(0);
        assertThat(day).containsEntry("date", iso(1));
        assertThat(((Number) day.get("tempMaxC")).doubleValue()).isEqualTo(19.0);
        assertThat(day).containsEntry("weatherCode", 3);
        assertThat(day)
                .as("the code in words, so the client does not need the WMO table")
                .containsEntry("summary", "Overcast");

        // A second read inside the TTL must not go out again — this is the whole
        // reason the forecast is a table and not a pass-through.
        assertThat(daysOf(weatherFor(alice, tripId))).hasSize(3);
        verify(weatherClient, times(1)).fetch(anyDouble(), anyDouble(), anyInt());
    }

    /**
     * The limitation, designed for rather than hidden.
     *
     * A trip next year gets no weather at all — not an empty placeholder, not a
     * zero, and above all not a request. Nothing is knowable about it, and the
     * cheapest correct behaviour is to not ask.
     */
    @Test
    void aTripBeyondTheForecastHorizonAsksForNothing() {
        Session alice = register("alice");
        Object tripId = tripFrom(alice, 200, 203);
        addPlace(alice, tripId, iso(200), "Somewhere later", LAT, LON);

        Map<String, Object> weather = weatherFor(alice, tripId);
        assertThat(weather).containsEntry("available", true);
        assertThat(daysOf(weather)).isEmpty();
        verify(weatherClient, never()).fetch(anyDouble(), anyDouble(), anyInt());
    }

    /**
     * A trip that straddles the horizon gets the days it can and nothing for the
     * rest, in one answer. This is the normal case for a fortnight abroad.
     */
    @Test
    void atripStraddlingTheHorizonGetsTheDaysThatExist() {
        // Only ten days ahead are knowable, whatever we ask for.
        when(weatherClient.fetch(anyDouble(), anyDouble(), anyInt())).thenReturn(horizonFrom(10));

        Session alice = register("alice");
        Object tripId = tripFrom(alice, 5, 20);
        addPlace(alice, tripId, iso(5), "Kyoto", LAT, LON);

        List<Map<String, Object>> days = daysOf(weatherFor(alice, tripId));
        assertThat(days)
                .as("days five to nine — inside our window and inside the upstream's")
                .hasSize(5);
        assertThat(days.get(0)).containsEntry("date", iso(5));
        assertThat(days.get(4)).containsEntry("date", iso(9));
    }

    /**
     * One call per location, not per day.
     *
     * A forecast response covers the whole horizon, so a trip that stays put
     * costs one call however long it is. This is the difference between a feature
     * that fits inside "less than 10'000 API calls per day" and one that does not.
     */
    @Test
    void aTripInOnePlaceCostsOneCallAndMovingOnCostsASecond() {
        when(weatherClient.fetch(eq(LAT), eq(LON), anyInt())).thenReturn(horizonFrom(16));
        when(weatherClient.fetch(eq(FAR_LAT), eq(FAR_LON), anyInt())).thenReturn(horizonFrom(16));

        Session alice = register("alice");
        Object tripId = tripFrom(alice, 1, 5);
        addPlace(alice, tripId, iso(1), "Kyoto", LAT, LON);
        addPlace(alice, tripId, iso(2), "Kyoto again", LAT, LON);
        addPlace(alice, tripId, iso(3), "Kyoto still", LAT, LON);
        addPlace(alice, tripId, iso(4), "Kanazawa", FAR_LAT, FAR_LON);

        assertThat(daysOf(weatherFor(alice, tripId))).hasSize(5);
        verify(weatherClient, times(1)).fetch(eq(LAT), eq(LON), anyInt());
        verify(weatherClient, times(1)).fetch(eq(FAR_LAT), eq(FAR_LON), anyInt());
    }

    /**
     * A day with nothing on it borrows from the nearest planned day, and does so
     * without costing a second call.
     *
     * An empty day is exactly the one you want a forecast for — it is the day you
     * are still deciding about. The location comes from the day before rather than
     * from the average of the trip's points, because on a two-city trip that
     * average is somewhere neither city is.
     */
    @Test
    void anEmptyDayBorrowsFromTheNearestPlannedDay() {
        when(weatherClient.fetch(eq(LAT), eq(LON), anyInt())).thenReturn(horizonFrom(16));

        Session alice = register("alice");
        Object tripId = tripFrom(alice, 1, 3);
        addPlace(alice, tripId, iso(2), "Kyoto", LAT, LON);

        // Day one is before anything is planned and day three is after; both get
        // Kyoto, and all three days come from the one call.
        assertThat(daysOf(weatherFor(alice, tripId))).hasSize(3);
        verify(weatherClient, times(1)).fetch(anyDouble(), anyDouble(), anyInt());
    }

    /**
     * Moving a day's first place refetches it.
     *
     * The stored row remembers the point it was fetched for, and it has to: a row
     * that only knew its own age would go on showing Kyoto's weather for a day now
     * spent in Kanazawa, and would keep doing so for the whole TTL.
     */
    @Test
    void movingADaysPlaceRefetchesItRatherThanShowingTheOldTownsWeather() {
        when(weatherClient.fetch(eq(LAT), eq(LON), anyInt())).thenReturn(horizonFrom(16));
        when(weatherClient.fetch(eq(FAR_LAT), eq(FAR_LON), anyInt()))
                .thenReturn(coldHorizonFrom(16));

        Session alice = register("alice");
        Object tripId = tripFrom(alice, 1, 2);
        addPlace(alice, tripId, iso(1), "Kyoto", LAT, LON);
        assertThat(((Number) daysOf(weatherFor(alice, tripId)).get(0).get("tempMaxC"))
                .doubleValue()).isEqualTo(19.0);

        // Same day, somewhere else entirely, well inside the TTL.
        Object kyotoId = firstPlaceIdOn(alice, tripId, iso(1));
        addPlace(alice, tripId, iso(1), "Kanazawa", FAR_LAT, FAR_LON);
        assertThat(delete(alice, "/api/trips/" + tripId + "/places/" + kyotoId)
                .getStatusCode().value()).isEqualTo(204);

        assertThat(((Number) daysOf(weatherFor(alice, tripId)).get(0).get("tempMaxC"))
                .doubleValue())
                .as("the new town's forecast, not the cached one for the old")
                .isEqualTo(2.0);
    }

    /** A trip with nothing located anywhere gets no weather rather than a guess. */
    @Test
    void aTripWithNoLocatedPlaceGetsNoWeatherAndAsksForNone() {
        Session alice = register("alice");
        Object tripId = tripFrom(alice, 1, 3);
        // A hand-typed place: a name and no coordinates.
        assertThat(post(alice, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"That place we talked about"}
                """.formatted(iso(1))).getStatusCode().value()).isEqualTo(201);

        assertThat(daysOf(weatherFor(alice, tripId))).isEmpty();
        verify(weatherClient, never()).fetch(anyDouble(), anyDouble(), anyInt());
    }

    /**
     * The forecast service being down is not this application being broken.
     *
     * The itinerary is the page; weather is decoration on it. So the endpoint
     * answers 200 with no days rather than 502 — which is the opposite of the
     * geocoder, where quietly returning nothing would read as "no such place".
     */
    @Test
    void aForecastServiceThatIsDownCostsTheDaysAndNothingElse() {
        when(weatherClient.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("connection refused"));

        Session alice = register("alice");
        Object tripId = tripFrom(alice, 1, 3);
        addPlace(alice, tripId, iso(1), "Kyoto", LAT, LON);

        Map<String, Object> weather = weatherFor(alice, tripId);
        assertThat(weather).containsEntry("available", true);
        assertThat(daysOf(weather)).isEmpty();
    }

    /** Somebody else's trip is a 404, as everywhere else. */
    @Test
    void aNonMemberCannotReadATripsWeather() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFrom(alice, 1, 3);

        assertThat(get(bob, "/api/trips/" + tripId + "/weather").getStatusCode().value())
                .isEqualTo(404);
    }
}
