package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.route.RouteClient;
import com.wander.route.RouteProfile;

/**
 * Sorting a day by route, over HTTP.
 *
 * `wander.routing.enabled` is switched on for this context only: the feature
 * ships off, because unlike every other upstream here there is no public
 * service anybody may lean on.
 *
 * The routing engine is replaced at the {@code RouteClient} seam, as the
 * geocoder, the enrichment, the forecast and the exchange rates all are — the
 * suite talks to nothing.
 */
@TestPropertySource(properties = "wander.routing.enabled=true")
class RouteIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private RouteClient routeClient;

    /**
     * The load-bearing test of this feature.
     *
     * Everything else here could pass and the design would still be wrong if a
     * proposal reached the database: a preview that wrote would put a
     * rearranged day on every other member's screen within the second, through
     * live sync, with no undo and no one having agreed to it.
     */
    @Test
    void previewWritesNothing() {
        Session alice = register("alice");
        Trip trip = threeStops(alice);
        // A matrix in which the planned order is the worst possible one.
        when(routeClient.table(any(), any())).thenReturn(reversing());

        Map<String, Object> preview = asMap(post(alice, route(trip, "WALKING"), "").getBody());

        assertThat(preview).containsEntry("changed", true);
        // Nijo and Kiyomizu are a minute apart in this matrix and Gion is a
        // quarter of an hour from either, so the day stops crossing the city
        // twice: 30 minutes of walking becomes 16.
        assertThat(namesOf(preview)).containsExactly("Nijo", "Kiyomizu", "Gion");
        assertThat(preview).containsEntry("currentSeconds", 1800);
        assertThat(preview).containsEntry("proposedSeconds", 960);
        assertThat(planned(alice, trip))
                .as("the day itself is untouched until somebody applies it")
                .containsExactly("Nijo", "Gion", "Kiyomizu");
    }

    @Test
    void applyingAProposalReordersTheDay() {
        Session alice = register("alice");
        Trip trip = threeStops(alice);

        var response = post(alice, order(trip),
                """
                        {"placeIds":[%d,%d,%d]}
                        """.formatted(trip.kiyomizu, trip.gion, trip.nijo));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(planned(alice, trip)).containsExactly("Kiyomizu", "Gion", "Nijo");
    }

    /**
     * A lock is a position, so it is enforced where positions are assigned —
     * not only in the optimiser. A client that ignored the flag and posted the
     * order anyway would otherwise make locking a suggestion.
     */
    @Test
    void aLockedPlaceCannotBeReorderedAwayFromItsPosition() {
        Session alice = register("alice");
        Trip trip = threeStops(alice);
        assertThat(put(alice, "/api/trips/" + trip.id + "/places/" + trip.nijo + "/locked",
                """
                        {"locked":true}
                        """).getStatusCode().value()).isEqualTo(200);

        var refused = post(alice, order(trip), """
                {"placeIds":[%d,%d,%d]}
                """.formatted(trip.kiyomizu, trip.gion, trip.nijo));

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody()).contains("Nijo");
        assertThat(planned(alice, trip)).containsExactly("Nijo", "Gion", "Kiyomizu");
    }

    /**
     * And the optimiser is told, so a proposal never suggests the refused
     * thing. Gion is the middle stop and the free answer above moves it to the
     * end; locked, it stays in the middle and the day is left as it is.
     */
    @Test
    void aLockedPlaceStaysWhereItIsInAProposal() {
        Session alice = register("alice");
        Trip trip = threeStops(alice);
        put(alice, "/api/trips/" + trip.id + "/places/" + trip.gion + "/locked", """
                {"locked":true}
                """);
        when(routeClient.table(any(), any())).thenReturn(reversing());

        Map<String, Object> preview = asMap(post(alice, route(trip, "WALKING"), "").getBody());

        assertThat(namesOf(preview)).containsExactly("Nijo", "Gion", "Kiyomizu");
        assertThat(stopsOf(preview).get(1)).containsEntry("locked", true);
        assertThat(stopsOf(preview).get(1)).containsEntry("pinned", true);
        assertThat(preview).as("nothing left to improve once the middle is pinned")
                .containsEntry("changed", false);
    }

    /**
     * A day whose places somebody else has changed since the proposal was made.
     * Refused rather than half-applied: any rule for where the missing place
     * goes is a rule that makes a broken reorder look like a working one.
     */
    @Test
    void anOrderThatDoesNotMatchTheDayIsRefused() {
        Session alice = register("alice");
        Trip trip = threeStops(alice);

        var refused = post(alice, order(trip), """
                {"placeIds":[%d,%d]}
                """.formatted(trip.gion, trip.nijo));

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(planned(alice, trip)).containsExactly("Nijo", "Gion", "Kiyomizu");
    }

    /**
     * A viewer may read the trip and may not reorder it, so asking for a
     * proposal would spend the instance's routing budget to produce something
     * they cannot use. The demo account on a public instance is exactly this.
     */
    @Test
    void aViewerCannotSortADayAndCostsNothingTrying() {
        Session alice = register("alice");
        Session bob = register("bob");
        Trip trip = threeStops(alice);
        assertThat(post(alice, "/api/trips/" + trip.id + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(bob.email())).getStatusCode().value()).isEqualTo(201);

        assertThat(post(bob, route(trip, "WALKING"), "").getStatusCode().value()).isEqualTo(403);
        assertThat(post(bob, order(trip), """
                {"placeIds":[%d,%d,%d]}
                """.formatted(trip.gion, trip.nijo, trip.kiyomizu)).getStatusCode().value())
                .isEqualTo(403);
        verify(routeClient, never()).table(any(), any());
    }

    /** Nothing to reorder, and saying so beats answering "already the best order". */
    @Test
    void aDayWithOneLocatedStopIsRefusedRatherThanCalledOptimal() {
        Session alice = register("alice");
        LocalDate day = LocalDate.now().plusDays(1);
        Object tripId = asMap(post(alice, "/api/trips", """
                {"name":"Thin day","startDate":"%s","endDate":"%s"}
                """.formatted(day, day.plusDays(1))).getBody()).get("id");
        post(alice, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"Only one","latitude":35.0,"longitude":135.7}
                """.formatted(day));
        // Typed by hand, so the engine was never told it exists.
        post(alice, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"Pick up tickets"}
                """.formatted(day));

        var refused = post(alice,
                "/api/trips/" + tripId + "/days/" + day + "/route/preview?profile=WALKING", "");

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        verify(routeClient, never()).table(any(), any());
    }

    /** The profile reaches the engine, or the three choices are decoration. */
    @Test
    void theChosenProfileAndTheDaysCoordinatesAreWhatIsAskedFor() {
        Session alice = register("alice");
        Trip trip = threeStops(alice);
        when(routeClient.table(any(), any())).thenReturn(reversing());

        post(alice, route(trip, "CYCLING"), "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RouteClient.Point>> points =
                ArgumentCaptor.forClass((Class<List<RouteClient.Point>>) (Class<?>) List.class);
        verify(routeClient).table(eq(RouteProfile.CYCLING), points.capture());
        // In the day's order, and latitude first on this side of the seam: the
        // longitude-first flip belongs to OSRM's URL and lives in the client.
        assertThat(points.getValue()).extracting(RouteClient.Point::latitude)
                .containsExactly(35.0117, 35.0037, 34.9949);
    }

    // -- helpers ------------------------------------------------------------

    private record Trip(Object id, LocalDate day, long nijo, long gion, long kiyomizu) {
    }

    /**
     * Three real places in Kyoto, planned in an order that crosses the city
     * twice: the castle in the north-west, the lanes in the middle, the temple
     * in the south-east — visited north-west, middle, south-east is one line,
     * and the day is created the other way round.
     */
    private Trip threeStops(Session owner) {
        LocalDate day = LocalDate.now().plusDays(1);
        Object tripId = asMap(post(owner, "/api/trips", """
                {"name":"Kyoto","startDate":"%s","endDate":"%s"}
                """.formatted(day, day.plusDays(2))).getBody()).get("id");
        long nijo = place(owner, tripId, day, "Nijo", 35.0117, 135.7481);
        long gion = place(owner, tripId, day, "Gion", 35.0037, 135.7788);
        long kiyomizu = place(owner, tripId, day, "Kiyomizu", 34.9949, 135.7850);
        return new Trip(tripId, day, nijo, gion, kiyomizu);
    }

    private long place(Session owner, Object tripId, LocalDate day, String name,
            double latitude, double longitude) {
        Map<String, Object> created = asMap(post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"%s","latitude":%s,"longitude":%s}
                """.formatted(day, name, latitude, longitude)).getBody());
        return ((Number) created.get("id")).longValue();
    }

    /**
     * A matrix that makes the planned order the expensive one: adjacent stops
     * are far apart and the ends are close, so the best route reverses the day.
     */
    private static RouteClient.Matrix reversing() {
        long[][] seconds = {
                { 0, 900, 60 },
                { 900, 0, 900 },
                { 60, 900, 0 },
        };
        long[][] metres = {
                { 0, 9000, 600 },
                { 9000, 0, 9000 },
                { 600, 9000, 0 },
        };
        return new RouteClient.Matrix(seconds, metres);
    }

    private static String route(Trip trip, String profile) {
        return "/api/trips/" + trip.id + "/days/" + trip.day + "/route/preview?profile=" + profile;
    }

    private static String order(Trip trip) {
        return "/api/trips/" + trip.id + "/days/" + trip.day + "/order";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stopsOf(Map<String, Object> preview) {
        return (List<Map<String, Object>>) preview.get("stops");
    }

    private static List<String> namesOf(Map<String, Object> preview) {
        return stopsOf(preview).stream().map(stop -> (String) stop.get("name")).toList();
    }

    /** The day as it is actually stored, read back through the itinerary. */
    @SuppressWarnings("unchecked")
    private List<String> planned(Session session, Trip trip) {
        Map<String, Object> itinerary =
                asMap(get(session, "/api/trips/" + trip.id + "/itinerary").getBody());
        for (Map<String, Object> day : (List<Map<String, Object>>) itinerary.get("days")) {
            if (trip.day.toString().equals(day.get("date"))) {
                return ((List<Map<String, Object>>) day.get("places")).stream()
                        .map(place -> (String) place.get("name"))
                        .toList();
            }
        }
        throw new AssertionError("The trip has no day " + trip.day);
    }
}
