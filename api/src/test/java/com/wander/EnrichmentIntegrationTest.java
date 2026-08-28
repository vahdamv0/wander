package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.enrich.EnrichmentClient;
import com.wander.enrich.PlaceFacts;

/**
 * Enrichment over HTTP, with the upstreams replaced.
 *
 * `EnrichmentClient` is mocked for the same reason `GeocoderClient` is: the four
 * services behind it have rate budgets and change their payloads, and neither
 * belongs in a test suite. What is tested here is the behaviour around them —
 * that a fetch happens once, that a place with nothing to ask about says so
 * calmly, and that a photo cannot be stored without the credit that makes showing
 * it permissible.
 */
class EnrichmentIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private EnrichmentClient enrichmentClient;

    private static final PlaceFacts FUSHIMI = new PlaceFacts(
            "Q11681", "Fushimi Inari-taisha",
            "A Shinto shrine at the base of Mount Inari.",
            "https://en.wikipedia.org/wiki/Fushimi_Inari-taisha", "CC BY-SA 4.0",
            "Mo-Su 00:00-24:00", "https://inari.jp", "+81 75-641-7331",
            List.of(new PlaceFacts.PhotoCandidate(
                    "https://upload.wikimedia.org/full.jpg",
                    "https://upload.wikimedia.org/thumb.jpg",
                    "A Photographer", "CC BY-SA 4.0",
                    "https://commons.wikimedia.org/wiki/File:Full.jpg")));

    /**
     * A reference nobody else in this class has used.
     *
     * `place_enrichment` is keyed by reference and shared across trips, users and
     * — since the database is not reset between tests — across tests. That is the
     * feature working, so each test brings its own place rather than fighting it.
     */
    private static String freshRef() {
        return "way/" + (100_000_000L + Long.parseLong(unique(), 16) % 100_000_000L);
    }

    private Object tripFor(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Kyoto","startDate":"2027-03-28","endDate":"2027-03-30"}
                """).getBody()).get("id");
    }

    private Object placeFrom(Session owner, Object tripId, String ref) {
        String body = ref == null
                ? """
                  {"dayDate":"2027-03-28","name":"That cafe we liked"}
                  """
                : """
                  {"dayDate":"2027-03-28","name":"Fushimi Inari","latitude":34.9671,
                   "longitude":135.7727,"osmRef":"%s"}
                  """.formatted(ref);
        return asMap(post(owner, "/api/trips/" + tripId + "/places", body).getBody()).get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> photosOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("photos");
    }

    @Test
    void aPlaceIsAskedAboutOnceAndRememberedAfterwards() {
        String ref = freshRef();
        when(enrichmentClient.fetch(eq(ref), any())).thenReturn(FUSHIMI);
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        Object placeId = placeFrom(owner, tripId, ref);

        var first = get(owner, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> view = asMap(first.getBody());
        assertThat(view).containsEntry("available", true)
                .containsEntry("title", "Fushimi Inari-taisha")
                .containsEntry("openingHours", "Mo-Su 00:00-24:00")
                .containsEntry("website", "https://inari.jp");
        // The summary never travels without its source and terms.
        assertThat(view.get("summary")).isNotNull();
        assertThat(view).containsEntry("summaryLicence", "CC BY-SA 4.0");
        assertThat((String) view.get("summaryUrl")).startsWith("https://en.wikipedia.org/");
        // And the hours carry the moment they were fetched, as their provenance.
        assertThat(view.get("fetchedAt")).isNotNull();

        assertThat(photosOf(view)).singleElement().satisfies(photo ->
                assertThat(photo).containsEntry("author", "A Photographer")
                        .containsEntry("licence", "CC BY-SA 4.0"));

        // Read again — and again from a second member's session, since the row is
        // about the place rather than about anybody's trip.
        get(owner, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment");
        verify(enrichmentClient, times(1)).fetch(eq(ref), any());
    }

    @Test
    void twoTripsWithTheSamePlaceShareOneFetch() {
        String ref = freshRef();
        when(enrichmentClient.fetch(eq(ref), any())).thenReturn(FUSHIMI);
        Session alice = register("alice");
        Session bob = register("bob");
        Object aliceTrip = tripFor(alice);
        Object bobTrip = tripFor(bob);
        Object alicePlace = placeFrom(alice, aliceTrip, ref);
        Object bobPlace = placeFrom(bob, bobTrip, ref);

        get(alice, "/api/trips/" + aliceTrip + "/places/" + alicePlace + "/enrichment");
        get(bob, "/api/trips/" + bobTrip + "/places/" + bobPlace + "/enrichment");

        // The shrine is the same shrine. Fetching it twice would spend four
        // services' rate budget to store the same paragraph again.
        verify(enrichmentClient, times(1)).fetch(eq(ref), any());
    }

    @Test
    void aPlaceTypedByHandHasNothingToAskAbout() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        Object placeId = placeFrom(owner, tripId, null);

        Map<String, Object> view = asMap(
                get(owner, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment").getBody());

        // Not an error — there is simply nothing upstream to ask, permanently.
        assertThat(view).containsEntry("available", false);
        assertThat(photosOf(view)).isEmpty();
        verify(enrichmentClient, never()).fetch(any(), any());
    }

    @Test
    void nothingFoundIsRememberedToo() {
        when(enrichmentClient.fetch(any(), any())).thenReturn(PlaceFacts.empty());
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        Object placeId = placeFrom(owner, tripId, freshRef());

        Map<String, Object> view = asMap(
                get(owner, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment").getBody());
        // `available` is about having looked, not about having found something.
        assertThat(view).containsEntry("available", true);
        assertThat(view.get("summary")).isNull();
        assertThat(photosOf(view)).isEmpty();

        get(owner, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment");
        // "We looked and there is nothing" is worth storing, or every popup on a
        // bus stop re-asks four services about it.
        verify(enrichmentClient, times(1)).fetch(any(), any());
    }

    @Test
    void aChosenPhotoIsStoredWithTheCreditTheServerFetched() {
        when(enrichmentClient.fetch(any(), any())).thenReturn(FUSHIMI);
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        Object placeId = placeFrom(owner, tripId, freshRef());
        get(owner, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment");

        // The request is a choice among what was offered, not a source of truth:
        // the author and licence it sends are ignored in favour of the ones this
        // instance actually fetched from Commons.
        var kept = put(owner, "/api/trips/" + tripId + "/places/" + placeId + "/photo", """
                {"url":"https://upload.wikimedia.org/full.jpg","author":"Someone Else",
                 "licence":"Public domain"}
                """);
        assertThat(kept.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(kept.getBody()))
                .containsEntry("photoAuthor", "A Photographer")
                .containsEntry("photoLicence", "CC BY-SA 4.0");
        assertThat((String) asMap(kept.getBody()).get("photoThumbUrl")).endsWith("thumb.jpg");

        // It rides along on the itinerary afterwards, which is where it earns its keep.
        assertThat(get(owner, "/api/trips/" + tripId + "/itinerary").getBody())
                .contains("A Photographer");

        // And it can be taken off again.
        var cleared = put(owner, "/api/trips/" + tripId + "/places/" + placeId + "/photo", """
                {"url":null}
                """);
        assertThat(asMap(cleared.getBody()).get("photoUrl")).isNull();
        assertThat(asMap(cleared.getBody()).get("photoAuthor")).isNull();
    }

    @Test
    void aPhotoThatWasNeverOfferedIsRefused() {
        when(enrichmentClient.fetch(any(), any())).thenReturn(FUSHIMI);
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        Object placeId = placeFrom(owner, tripId, freshRef());
        get(owner, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment");

        // Otherwise the endpoint stores any URL with any attribution text somebody
        // sends, which is how an instance ends up hotlinking a mis-credited image.
        assertThat(put(owner, "/api/trips/" + tripId + "/places/" + placeId + "/photo", """
                {"url":"https://example.com/somebody-elses.jpg","author":"Me","licence":"Mine"}
                """).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aViewerReadsAndAStrangerDoesNot() {
        when(enrichmentClient.fetch(any(), any())).thenReturn(FUSHIMI);
        Session owner = register("owner");
        Session viewer = register("viewer");
        Session stranger = register("stranger");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email()));
        Object placeId = placeFrom(owner, tripId, freshRef());

        assertThat(get(viewer, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment")
                .getStatusCode().value()).isEqualTo(200);
        // Keeping a photo changes the trip, so it is a write like any other.
        assertThat(put(viewer, "/api/trips/" + tripId + "/places/" + placeId + "/photo", """
                {"url":"https://upload.wikimedia.org/full.jpg"}
                """).getStatusCode().value()).isEqualTo(403);

        assertThat(get(stranger, "/api/trips/" + tripId + "/places/" + placeId + "/enrichment")
                .getStatusCode().value()).isEqualTo(404);
    }
}
