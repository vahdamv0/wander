package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.route.RouteClient;

/**
 * The default instance: no routing engine configured, so no route sorting.
 *
 * No {@code @TestPropertySource} here, unlike {@link WeatherDisabledIntegrationTest}
 * — off *is* the default for this one, and asserting it against the ordinary
 * test context is the point. There is no public routing service to lean on, so
 * an instance only gets this feature when its operator stands an OSRM up.
 *
 * Two halves that have to agree, the pair the weather switch already
 * establishes: the endpoint refuses, and the client is told not to offer the
 * button.
 */
class RoutingDisabledIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RouteClient routeClient;

    @Test
    void sortingIsRefusedAndNothingGoesOut() {
        Session alice = register("alice");
        LocalDate day = LocalDate.now().plusDays(1);
        Object tripId = asMap(post(alice, "/api/trips", """
                {"name":"No routing","startDate":"%s","endDate":"%s"}
                """.formatted(day, day.plusDays(1))).getBody()).get("id");
        post(alice, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"Nijo","latitude":35.0117,"longitude":135.7481}
                """.formatted(day));
        post(alice, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"Gion","latitude":35.0037,"longitude":135.7788}
                """.formatted(day));

        var response = post(alice,
                "/api/trips/" + tripId + "/days/" + day + "/route/preview?profile=WALKING", "");

        // 503, not a 200 saying "no change": a sort that quietly answers
        // "already the best order" makes a claim about somebody's day.
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verify(routeClient, never()).table(any(), any());
    }

    @Test
    void theClientIsToldNotToOfferIt() {
        Session alice = register("alice");

        Map<String, Object> config = asMap(get(alice, "/api/config").getBody());

        assertThat(config).containsEntry("routingEnabled", false);
    }

    /**
     * Locking still works with the engine off, and that is deliberate rather
     * than an oversight: a lock is a fact about a place, an operator may switch
     * routing on later, and a control that only half exists is worse than one
     * that always does.
     */
    @Test
    void aPlaceCanStillBeLocked() {
        Session alice = register("alice");
        LocalDate day = LocalDate.now().plusDays(1);
        Object tripId = asMap(post(alice, "/api/trips", """
                {"name":"Locks anyway","startDate":"%s","endDate":"%s"}
                """.formatted(day, day.plusDays(1))).getBody()).get("id");
        Object placeId = asMap(post(alice, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"Hotel","latitude":35.0,"longitude":135.7}
                """.formatted(day)).getBody()).get("id");

        Map<String, Object> locked = asMap(put(alice,
                "/api/trips/" + tripId + "/places/" + placeId + "/locked", """
                        {"locked":true}
                        """).getBody());

        assertThat(locked).containsEntry("locked", true);
    }
}
