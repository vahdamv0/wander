package com.wander.weather;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.wander.config.WanderProperties;
import com.wander.geo.RateGate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Open-Meteo over HTTP.
 *
 * Chosen because it needs no API key: a self-hoster should not have to register
 * an account with a weather company to see whether it will rain on day three.
 * The free tier is CC BY 4.0 and non-commercial, which is why the attribution
 * travels to the client from configuration alongside the tile attribution — see
 * {@code WanderProperties.Weather}.
 *
 * The response is a **columnar** JSON object: parallel arrays under `daily`,
 * indexed by position, rather than a list of day objects. That is worth naming
 * because it is the one thing about this upstream that is easy to mis-read, and
 * a short array is not an error — it is how the horizon shows up.
 *
 * Unlike the geocoder, an outage here is swallowed by the service above rather
 * than turned into a 502: weather is decoration on a page whose job is the
 * itinerary, so the page renders without it.
 */
@Component
public class OpenMeteoWeatherClient implements WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherClient.class);

    private final RestClient http;
    private final ObjectMapper json;
    private final RateGate gate;

    public OpenMeteoWeatherClient(WanderProperties properties, ObjectMapper json,
            RateGate openMeteoGate) {
        this.json = json;
        this.gate = openMeteoGate;
        this.http = RestClient.builder()
                .baseUrl(properties.weather().baseUrl())
                // Same reason as the geocoder's: an identifying agent is the
                // etiquette, and it is what gets this instance a warning rather
                // than a block.
                .defaultHeader("User-Agent",
                        "wander/" + properties.version() + " (self-hosted travel planner)")
                .defaultHeader("Accept", "application/json")
                .requestFactory(timeouts())
                .build();
    }

    private static JdkClientHttpRequestFactory timeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(6));
        return factory;
    }

    @Override
    public List<DailyForecast> fetch(double latitude, double longitude, int days) {
        gate.pass();
        String body = http.get()
                .uri(uri -> forecastUri(uri, latitude, longitude, days))
                .retrieve()
                .body(String.class);
        return parse(body);
    }

    private static URI forecastUri(UriBuilder uri, double latitude, double longitude, int days) {
        return uri.path("/v1/forecast")
                .queryParam("latitude", latitude)
                .queryParam("longitude", longitude)
                .queryParam("daily", "temperature_2m_max,temperature_2m_min,weather_code")
                // Required whenever `daily` is asked for, and `auto` is the only
                // correct answer here: a day of a trip is a day in the place the
                // trip is, not a slice of this server's clock.
                .queryParam("timezone", "auto")
                .queryParam("forecast_days", days)
                .build();
    }

    /**
     * Package-private so the columnar mapping can be tested without the network,
     * which is where the actual risk in this class lives.
     */
    List<DailyForecast> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode daily;
        try {
            daily = json.readTree(body).path("daily");
        } catch (RuntimeException ex) {
            log.warn("Weather service answered with something that is not JSON: {}",
                    ex.getMessage());
            return List.of();
        }

        JsonNode dates = daily.path("time");
        JsonNode highs = daily.path("temperature_2m_max");
        JsonNode lows = daily.path("temperature_2m_min");
        JsonNode codes = daily.path("weather_code");

        List<DailyForecast> forecasts = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            // A day missing any of the three is skipped rather than guessed at.
            // The arrays are supposed to be the same length; a hole in one of them
            // is exactly the sort of thing a columnar format makes possible.
            if (!isNumber(highs, i) || !isNumber(lows, i) || !isNumber(codes, i)) {
                continue;
            }
            LocalDate date = parseDate(dates.get(i));
            if (date == null) {
                continue;
            }
            forecasts.add(new DailyForecast(date, highs.get(i).asDouble(), lows.get(i).asDouble(),
                    codes.get(i).asInt()));
        }
        return List.copyOf(forecasts);
    }

    private static boolean isNumber(JsonNode array, int index) {
        JsonNode value = array.get(index);
        return value != null && value.isNumber();
    }

    private static LocalDate parseDate(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asString());
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
