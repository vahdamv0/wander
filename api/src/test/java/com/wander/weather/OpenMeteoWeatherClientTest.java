package com.wander.weather;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wander.config.WanderProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * The columnar parse, which is the only interesting part of this class.
 *
 * Open-Meteo answers with parallel arrays under `daily` rather than a list of day
 * objects, so a day is assembled by index across four arrays. Every failure mode
 * that shape allows is here, from a recorded response — no network, for the same
 * reason `NominatimClientTest` has none.
 */
class OpenMeteoWeatherClientTest {

    private final OpenMeteoWeatherClient client = new OpenMeteoWeatherClient(
            new WanderProperties("test", "", "", true, "EUR", null, null, null, null, null, null,
                    new WanderProperties.Weather(true, "http://localhost:1", 16, 180,
                            "Weather data by Open-Meteo.com (CC BY 4.0)",
                            "https://open-meteo.com/", 0, 1000),
                    null),
            JsonMapper.builder().build(),
            new com.wander.geo.RateGate(0, 1000));

    /** Trimmed from a real answer for Kyoto. */
    private static final String KYOTO = """
            {
              "latitude": 35.0,
              "longitude": 135.75,
              "timezone": "Asia/Tokyo",
              "daily_units": {"temperature_2m_max": "°C"},
              "daily": {
                "time": ["2027-03-28", "2027-03-29", "2027-03-30"],
                "temperature_2m_max": [18.4, 15.1, 21.0],
                "temperature_2m_min": [7.2, 6.8, 9.9],
                "weather_code": [3, 61, 0]
              }
            }
            """;

    @Test
    void aDayIsAssembledAcrossTheParallelArrays() {
        List<WeatherClient.DailyForecast> forecasts = client.parse(KYOTO);

        assertThat(forecasts).hasSize(3);
        assertThat(forecasts.get(1))
                .as("the second entry of every array belongs to the second day")
                .isEqualTo(new WeatherClient.DailyForecast(
                        LocalDate.of(2027, 3, 29), 15.1, 6.8, 61));
    }

    @Test
    void aShortAnswerIsTheHorizonRatherThanAnError() {
        // Asking for sixteen days and being given three is normal: the upstream
        // decides how far it can see. Nothing here should treat it as a failure.
        List<WeatherClient.DailyForecast> forecasts = client.parse("""
                {"daily": {"time": ["2027-03-28"], "temperature_2m_max": [18.4],
                 "temperature_2m_min": [7.2], "weather_code": [3]}}
                """);

        assertThat(forecasts).hasSize(1);
    }

    @Test
    void aDayMissingAnyOfItsValuesIsSkippedRatherThanGuessedAt() {
        // A hole in one column is precisely what a columnar format makes
        // possible, and a null high must not become a zero on somebody's card.
        List<WeatherClient.DailyForecast> forecasts = client.parse("""
                {"daily": {
                  "time": ["2027-03-28", "2027-03-29", "2027-03-30"],
                  "temperature_2m_max": [18.4, null, 21.0],
                  "temperature_2m_min": [7.2, 6.8, 9.9],
                  "weather_code": [3, 61, null]
                }}
                """);

        assertThat(forecasts).extracting(WeatherClient.DailyForecast::date)
                .as("only the day whose every column had a value")
                .containsExactly(LocalDate.of(2027, 3, 28));
    }

    @Test
    void nothingUsableIsAnEmptyListAndNotAnException() {
        assertThat(client.parse(null)).isEmpty();
        assertThat(client.parse("")).isEmpty();
        assertThat(client.parse("not json at all")).isEmpty();
        assertThat(client.parse("{\"error\":true,\"reason\":\"No data\"}")).isEmpty();
    }

    /**
     * Not exhaustive on purpose — the codes are grouped, because a card that
     * distinguished light drizzle from moderate drizzle is a card nobody reads.
     */
    @Test
    void codesBecomeWordsAndAnUnknownOneSaysNothing() {
        assertThat(WeatherCodes.describe(0)).isEqualTo("Clear");
        assertThat(WeatherCodes.describe(3)).isEqualTo("Overcast");
        assertThat(WeatherCodes.describe(61)).isEqualTo("Rain");
        assertThat(WeatherCodes.describe(75)).isEqualTo("Snow");
        assertThat(WeatherCodes.describe(95)).isEqualTo("Thunderstorm");
        assertThat(WeatherCodes.describe(42))
                .as("blank, not a made-up category — the day is not blank, our wording for it is")
                .isEmpty();
    }
}
