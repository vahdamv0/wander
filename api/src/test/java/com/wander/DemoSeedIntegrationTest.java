package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The seeded demo trip, and the promise that the published account can only read
 * it.
 *
 * That promise is the whole feature. The credentials are printed on the sign-in
 * page of a public instance, so "viewer" has to be enforced by the server rather
 * than by which buttons the client draws — and nothing else in the suite would
 * notice if the seeder handed out `EDITOR` one day, because every write would
 * simply start succeeding.
 *
 * Its own context (the property override gives it one) because seeding runs at
 * boot and writes rows that the rest of the suite has no reason to see.
 */
@TestPropertySource(properties = {
        "wander.demo.enabled=true",
        "wander.demo.email=demo-seed-test@wander.local",
        "wander.demo.password=demo-traveller" })
class DemoSeedIntegrationTest extends IntegrationTestBase {

    private Session demo() {
        return login("demo-seed-test@wander.local", "demo-traveller");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> theTrip(Session session) {
        List<Map<String, Object>> trips = json.readValue(
                get(session, "/api/trips").getBody(), List.class);
        assertThat(trips).hasSize(1);
        return trips.get(0);
    }

    @Test
    void thePublishedAccountCanSignInAndSeeOneSeededTrip() {
        Map<String, Object> trip = theTrip(demo());
        assertThat(trip).containsEntry("name", "Japan in Autumn");
        assertThat(trip).containsEntry("myRole", "VIEWER");
    }

    /**
     * The load-bearing one. A viewer gets 403 rather than 404, because being
     * refused a write is a different thing from not being on the trip at all.
     */
    @Test
    void thePublishedAccountCannotChangeAnything() {
        Session demo = demo();
        Object id = theTrip(demo).get("id");

        assertThat(post(demo, "/api/trips/" + id + "/places",
                "{\"dayDate\":\"2030-01-01\",\"name\":\"Somewhere\"}").getStatusCode().value())
                .isEqualTo(403);
        assertThat(put(demo, "/api/trips/" + id,
                "{\"name\":\"Renamed\",\"startDate\":\"2030-01-01\",\"endDate\":\"2030-01-05\"}")
                .getStatusCode().value()).isEqualTo(403);
        assertThat(delete(demo, "/api/trips/" + id).getStatusCode().value()).isEqualTo(403);
    }

    /**
     * The dates are computed from the day it was seeded, not written down. A
     * fixed range would drift out of the forecast horizon within a fortnight and
     * the day cards would quietly lose their weather — the failure this whole
     * re-seed-on-boot design exists to avoid.
     */
    @Test
    void theTripIsDatedFromWhenItWasSeeded() {
        Map<String, Object> trip = theTrip(demo());
        assertThat(java.time.LocalDate.parse((String) trip.get("startDate")))
                .isAfterOrEqualTo(java.time.LocalDate.now())
                .isBefore(java.time.LocalDate.now().plusDays(7));
    }

    /** The content that makes the demo worth looking at, rather than an empty trip. */
    @SuppressWarnings("unchecked")
    @Test
    void theTripCarriesTheThingsWorthDemonstrating() {
        Session demo = demo();
        Object id = theTrip(demo).get("id");

        Map<String, Object> itinerary = asMap(get(demo, "/api/trips/" + id + "/itinerary").getBody());
        List<Map<String, Object>> days = (List<Map<String, Object>>) itinerary.get("days");
        assertThat(days).hasSize(10);

        List<Map<String, Object>> allPlaces = days.stream()
                .flatMap(day -> ((List<Map<String, Object>>) day.get("places")).stream()).toList();
        assertThat(allPlaces).hasSize(10);
        // Seeded from real geocoder references, so the enrichment panel works on
        // a demo place exactly as it does on one somebody searched for.
        assertThat(allPlaces).allSatisfy(place -> assertThat(place).containsEntry("enrichable", true));
        // A photo is only stored with its credit, so any place that has one has both.
        assertThat(allPlaces).filteredOn(place -> place.get("photoUrl") != null)
                .isNotEmpty()
                .allSatisfy(place -> {
                    assertThat((String) place.get("photoAuthor")).isNotBlank();
                    assertThat((String) place.get("photoLicence")).isNotBlank();
                });
        // Some days have a note and some do not; an untouched day is the normal case.
        assertThat(days).filteredOn(day -> day.get("note") != null).isNotEmpty().hasSizeLessThan(10);

        assertThat((List<?>) asMap(get(demo, "/api/trips/" + id + "/reservations").getBody())
                .get("reservations")).hasSize(5);
        assertThat((List<?>) asMap(get(demo, "/api/trips/" + id + "/expenses").getBody())
                .get("expenses")).hasSize(8);
    }
}
