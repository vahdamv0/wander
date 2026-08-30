package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.geo.GeocoderClient;
import com.wander.geo.dto.PlaceSuggestion;

/**
 * One person cannot spend the whole instance's upstream budget.
 *
 * The endpoints that reach Nominatim, Wikimedia and Open-Meteo run on donated
 * capacity that belongs to the instance rather than to a user, and
 * authentication alone only means the person spending it has an account —
 * which, once sign-ups are open, is not much of a hurdle.
 *
 * **The assertion that carries the whole design is the second one.** A limit
 * that stopped everybody at once would be indistinguishable from the
 * application-wide {@code RateGate} that was already there, and that gate is the
 * problem rather than the protection: it parks the request thread, so one heavy
 * caller queues everybody else's searches behind their own. Proving a *second*
 * user is unaffected is what proves this is per caller.
 *
 * Its own context, and for the reason every throttle test here needs one: a
 * single counter bean is shared by every test in a context.
 */
@TestPropertySource(properties = {
        "wander.quota.searches-per-window=3",
        "wander.quota.window-minutes=5" })
class UpstreamQuotaIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private GeocoderClient geocoder;

    private static final PlaceSuggestion KIYOMIZU = new PlaceSuggestion(
            "way/44708819", "Kiyomizu-dera",
            "Kiyomizu-dera, Higashiyama, Kyoto, Japan",
            34.9949, 135.7850, "attraction");

    private int search(Session user) {
        return get(user, URI.create("/api/geo/search?q=kiyomizu")).getStatusCode().value();
    }

    @Test
    void oneCallerRunsOutOfSearchesAndEverybodyElseCarriesOn() {
        when(geocoder.search(anyString(), anyInt(), anyString())).thenReturn(List.of(KIYOMIZU));

        Session heavy = register("heavy");
        Session bystander = register("bystander");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(search(heavy)).as("search %d, inside the quota", attempt + 1).isEqualTo(200);
        }

        assertThat(search(heavy)).as("the fourth search").isEqualTo(429);

        // The half that matters: the quota is the heavy caller's, not the
        // instance's. If this ever returns 429, the limit has become a global
        // one and one user can take place search down for everybody.
        assertThat(search(bystander)).as("a different user, untouched").isEqualTo(200);
    }
}
