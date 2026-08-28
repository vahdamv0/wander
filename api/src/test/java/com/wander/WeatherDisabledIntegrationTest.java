package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.weather.WeatherClient;

/**
 * An instance with weather switched off.
 *
 * Its own context, like {@link RegistrationDisabledIntegrationTest}, because the
 * switch is read at configuration time. Two halves that have to agree: the
 * endpoint says unavailable, and the client is told not to ask in the first place
 * — an instance with no outbound network is a supported configuration, and a page
 * that made a doomed request per trip would be a bug nobody saw.
 */
@TestPropertySource(properties = "wander.weather.enabled=false")
class WeatherDisabledIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private WeatherClient weatherClient;

    @Test
    @SuppressWarnings("unchecked")
    void theEndpointSaysUnavailableAndNothingGoesOut() {
        Session alice = register("alice");
        String start = LocalDate.now().plusDays(1).toString();
        Object tripId = asMap(post(alice, "/api/trips", """
                {"name":"No forecast","startDate":"%s","endDate":"%s"}
                """.formatted(start, LocalDate.now().plusDays(3))).getBody()).get("id");
        assertThat(post(alice, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"Kyoto","latitude":35.0,"longitude":135.75}
                """.formatted(start)).getStatusCode().value()).isEqualTo(201);

        Map<String, Object> weather =
                asMap(get(alice, "/api/trips/" + tripId + "/weather").getBody());

        assertThat(weather).containsEntry("available", false);
        assertThat((List<Map<String, Object>>) weather.get("days")).isEmpty();
        assertThat((String) weather.get("attribution"))
                .as("no data, so nothing to credit")
                .isEmpty();
        verify(weatherClient, never()).fetch(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void theClientIsToldNotToAsk() {
        Session alice = register("alice");

        assertThat(asMap(get(alice, "/api/config").getBody()))
                .containsEntry("weatherEnabled", false);
    }
}
