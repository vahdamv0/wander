package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.common.UpstreamUnavailableException;
import com.wander.geo.GeocoderClient;
import com.wander.geo.dto.PlaceSuggestion;

/**
 * The search proxy, with the geocoder itself stubbed out.
 *
 * Stubbing is the point: a suite that really called Nominatim would need a
 * network connection to pass, and would spend a free public service's rate
 * budget every time anyone ran the build. {@link GeocoderClient} exists as an
 * interface for exactly this seam.
 */
class GeoSearchIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private GeocoderClient geocoder;

    private static final PlaceSuggestion SAGRADA = new PlaceSuggestion(
            "way/34633854", "Sagrada Família",
            "Sagrada Família, Carrer de Mallorca, Barcelona, Spain",
            41.4036, 2.1744, "attraction");

    /** Encodes the query itself, so a space reaches the server as a space. */
    private URI searchUri(String query) {
        return URI.create("/api/geo/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    @Test
    void aSuggestionIsProxiedThroughToTheClient() {
        Session user = register("quinn");
        when(geocoder.search(eq("sagrada"), anyInt())).thenReturn(List.of(SAGRADA));

        var response = get(user, searchUri("sagrada"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> hits = asList(response.getBody());
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst())
                .containsEntry("ref", "way/34633854")
                .containsEntry("name", "Sagrada Família")
                .containsEntry("category", "attraction")
                .containsEntry("latitude", 41.4036)
                .containsEntry("longitude", 2.1744);
    }

    @Test
    void theSameQueryIsOnlyAskedUpstreamOnce() {
        Session user = register("rosa");
        when(geocoder.search(eq("park guell"), anyInt())).thenReturn(List.of(SAGRADA));

        // Same question three ways: the cache key is case- and space-normalised,
        // because a typeahead sends all three within a second of each other.
        assertThat(get(user, searchUri("park guell")).getStatusCode().value()).isEqualTo(200);
        assertThat(get(user, searchUri("Park Guell")).getStatusCode().value()).isEqualTo(200);
        assertThat(get(user, searchUri("park  guell ")).getStatusCode().value()).isEqualTo(200);

        // One outbound request for three searches — which is what keeps the
        // one-per-second budget usable.
        verify(geocoder, times(1)).search(eq("park guell"), anyInt());
    }

    @Test
    void aQueryTooShortToRankIsRejectedWithoutCallingUpstream() {
        Session user = register("sami");

        assertThat(get(user, searchUri("ba")).getStatusCode().value()).isEqualTo(400);
        // No `q` at all is the client's bug, not the geocoder's problem.
        assertThat(get(user, "/api/geo/search").getStatusCode().value()).isEqualTo(400);
        verify(geocoder, times(0)).search(org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    @Test
    void aFailingGeocoderIsA502NotA500() {
        Session user = register("tomas");
        when(geocoder.search(eq("nowhere at all"), anyInt()))
                .thenThrow(new UpstreamUnavailableException("The place search service did not answer"));

        var response = get(user, searchUri("nowhere at all"));
        // This instance is fine; the service it called is not, and the client
        // should offer a retry rather than an error page.
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(asMap(response.getBody())).containsEntry("status", 502);
    }

    @Test
    void searchIsNotAnOpenProxy() {
        // Anonymous callers would otherwise spend this instance's rate budget.
        var response = http().get().uri("/api/geo/search?q=barcelona").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
