package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The itinerary contract: days come from the trip's range, ranks stay dense
 * through every kind of edit, and a place is reachable only through a trip the
 * caller is a member of.
 */
class PlaceIntegrationTest extends IntegrationTestBase {

    /** A three-day trip owned by a fresh user. */
    private Object newTrip(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Barcelona","startDate":"2027-11-03","endDate":"2027-11-05"}
                """).getBody()).get("id");
    }

    private Object addPlace(Session session, Object tripId, String day, String name) {
        var response = post(session, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"%s"}
                """.formatted(day, name));
        assertThat(response.getStatusCode().value()).as("create %s", name).isEqualTo(201);
        return asMap(response.getBody()).get("id");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> day(Session session, Object tripId, int index) {
        Map<String, Object> itinerary = asMap(get(session, "/api/trips/" + tripId + "/itinerary").getBody());
        List<Map<String, Object>> days = (List<Map<String, Object>>) itinerary.get("days");
        return (List<Map<String, Object>>) days.get(index).get("places");
    }

    private static List<String> names(List<Map<String, Object>> places) {
        return places.stream().map(place -> (String) place.get("name")).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyDayOfTheRangeIsPresentEvenWhenEmpty() {
        Session owner = register("iris");
        Object tripId = newTrip(owner);

        Map<String, Object> itinerary = asMap(get(owner, "/api/trips/" + tripId + "/itinerary").getBody());
        assertThat((Map<String, Object>) itinerary.get("trip")).containsEntry("dayCount", 3);

        List<Map<String, Object>> days = (List<Map<String, Object>>) itinerary.get("days");
        assertThat(days).hasSize(3);
        assertThat(days.get(0)).containsEntry("date", "2027-11-03").containsEntry("index", 1);
        assertThat(days.get(2)).containsEntry("date", "2027-11-05").containsEntry("index", 3);
        // An empty day is a day with no places, not a missing day.
        assertThat((List<Object>) days.get(1).get("places")).isEmpty();
    }

    @Test
    void placesAppendInOrderAndRenumberAfterADelete() {
        Session owner = register("jonas");
        Object tripId = newTrip(owner);

        addPlace(owner, tripId, "2027-11-03", "Sagrada Familia");
        Object park = addPlace(owner, tripId, "2027-11-03", "Park Guell");
        addPlace(owner, tripId, "2027-11-03", "La Boqueria");

        assertThat(names(day(owner, tripId, 0)))
                .containsExactly("Sagrada Familia", "Park Guell", "La Boqueria");
        assertThat(day(owner, tripId, 0)).extracting(place -> place.get("position"))
                .containsExactly(0, 1, 2);

        assertThat(delete(owner, "/api/trips/" + tripId + "/places/" + park).getStatusCode().value())
                .isEqualTo(204);

        // The hole the delete left is closed, so ranks stay dense.
        assertThat(day(owner, tripId, 0)).extracting(place -> place.get("position"))
                .containsExactly(0, 1);
        assertThat(names(day(owner, tripId, 0))).containsExactly("Sagrada Familia", "La Boqueria");
    }

    @Test
    void aPlaceMovesWithinADayAndBetweenDays() {
        Session owner = register("kai");
        Object tripId = newTrip(owner);

        Object first = addPlace(owner, tripId, "2027-11-03", "First");
        addPlace(owner, tripId, "2027-11-03", "Second");
        addPlace(owner, tripId, "2027-11-03", "Third");

        // Down one: the classic ↓ button, expressed as a target rank.
        post(owner, "/api/trips/" + tripId + "/places/" + first + "/move", """
                {"dayDate":"2027-11-03","position":1}
                """);
        assertThat(names(day(owner, tripId, 0))).containsExactly("Second", "First", "Third");

        // Across days, onto an empty one.
        var moved = post(owner, "/api/trips/" + tripId + "/places/" + first + "/move", """
                {"dayDate":"2027-11-05","position":0}
                """);
        assertThat(moved.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(moved.getBody())).containsEntry("dayDate", "2027-11-05")
                .containsEntry("position", 0);

        assertThat(names(day(owner, tripId, 0))).containsExactly("Second", "Third");
        assertThat(day(owner, tripId, 0)).extracting(place -> place.get("position"))
                .containsExactly(0, 1);
        assertThat(names(day(owner, tripId, 2))).containsExactly("First");

        // A position past the end of the day clamps rather than failing.
        post(owner, "/api/trips/" + tripId + "/places/" + first + "/move", """
                {"dayDate":"2027-11-03","position":99}
                """);
        assertThat(names(day(owner, tripId, 0))).containsExactly("Second", "Third", "First");
    }

    @Test
    void aPlaceCanBeRenamedAndAnnotated() {
        Session owner = register("lena");
        Object tripId = newTrip(owner);
        Object place = addPlace(owner, tripId, "2027-11-04", "Bar");

        var updated = put(owner, "/api/trips/" + tripId + "/places/" + place, """
                {"name":"Bar Cañete","notes":"Book ahead, opens 19:30"}
                """);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(updated.getBody()))
                .containsEntry("name", "Bar Cañete")
                .containsEntry("notes", "Book ahead, opens 19:30")
                // Editing text leaves the day and the rank alone.
                .containsEntry("dayDate", "2027-11-04")
                .containsEntry("position", 0);
    }

    @Test
    void aDayOutsideTheTripRangeIsRejected() {
        Session owner = register("mira");
        Object tripId = newTrip(owner);

        var response = post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-12-25","name":"Somewhere else entirely"}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void placesAreInvisibleToNonMembers() {
        Session owner = register("nils");
        Session stranger = register("olive");
        Object tripId = newTrip(owner);
        Object place = addPlace(owner, tripId, "2027-11-03", "Private plan");

        // 404 everywhere, not 403 — the same rule the trip itself follows.
        assertThat(get(stranger, "/api/trips/" + tripId + "/itinerary").getStatusCode().value())
                .isEqualTo(404);
        assertThat(post(stranger, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-11-03","name":"Sneaky"}
                """).getStatusCode().value()).isEqualTo(404);
        assertThat(delete(stranger, "/api/trips/" + tripId + "/places/" + place)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aPlaceIdFromAnotherTripIsNotReachable() {
        Session owner = register("piet");
        Object mine = newTrip(owner);
        Object other = newTrip(owner);
        // Both trips belong to the same person, so membership is not the gate here
        // — the place lookup being scoped by trip id is.
        Object place = addPlace(owner, mine, "2027-11-03", "Belongs to the first trip");

        assertThat(delete(owner, "/api/trips/" + other + "/places/" + place)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aPlaceFromASearchKeepsItsCoordinates() {
        Session owner = register("rhea");
        Object tripId = newTrip(owner);

        var created = post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-11-03","name":"Sagrada Familia",
                 "latitude":41.4036,"longitude":2.1744,
                 "address":"Sagrada Familia, Carrer de Mallorca, Barcelona, Spain"}
                """);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(asMap(created.getBody()))
                .containsEntry("latitude", 41.4036)
                .containsEntry("longitude", 2.1744)
                .containsEntry("address", "Sagrada Familia, Carrer de Mallorca, Barcelona, Spain");

        // And they survive the read the page actually makes.
        assertThat(day(owner, tripId, 0).getFirst()).containsEntry("latitude", 41.4036);
    }

    @Test
    void aPlaceTypedByHandHasNoCoordinatesAndThatIsFine() {
        Session owner = register("sven");
        Object tripId = newTrip(owner);
        addPlace(owner, tripId, "2027-11-03", "That cafe we liked");

        // Present but null, since Jackson serialises the absent location — which
        // is the same thing to the generated client, where both fields are
        // optional.
        assertThat(day(owner, tripId, 0).getFirst())
                .containsEntry("latitude", null)
                .containsEntry("longitude", null)
                .containsEntry("address", null);
    }

    @Test
    void halfACoordinatePairIsRejected() {
        Session owner = register("tara");
        Object tripId = newTrip(owner);

        // A latitude with no longitude is not a location. Caught in the service,
        // so it is a 400 rather than the database CHECK turning into a 500.
        var response = post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-11-03","name":"Half a point","latitude":41.4036}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anImpossibleLatitudeIsRejected() {
        Session owner = register("ugo");
        Object tripId = newTrip(owner);

        var response = post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-11-03","name":"Off the planet","latitude":91,"longitude":0}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aPlaceFromSearchKeepsTheGeocodersReference() throws Exception {
        Session owner = register("owner");
        Object tripId = asMap(post(owner, "/api/trips", """
                {"name":"Kyoto","startDate":"2027-03-28","endDate":"2027-03-30"}
                """).getBody()).get("id");

        // What the client sends when somebody picks a suggestion: the point *and*
        // the reference it came with.
        var fromSearch = post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-03-28","name":"Fushimi Inari","latitude":34.9671,
                 "longitude":135.7727,"address":"Fushimi Ward, Kyoto","osmRef":"way/34633854",
                 "category":"attraction"}
                """);
        assertThat(fromSearch.getStatusCode().value()).isEqualTo(201);
        // The reference itself never comes back — the client has no use for it,
        // and the server is what does the asking. The category does: it is shown.
        assertThat(asMap(fromSearch.getBody())).containsEntry("enrichable", true)
                .containsEntry("category", "attraction")
                .doesNotContainKey("osmRef");

        // Typed by hand: no reference, and that is permanent rather than missing.
        var typed = post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-03-28","name":"That cafe we liked"}
                """);
        assertThat(asMap(typed.getBody())).containsEntry("enrichable", false);
        assertThat(asMap(typed.getBody()).get("photoUrl")).isNull();
        // No category either, and none invented from the name.
        assertThat(asMap(typed.getBody()).get("category")).isNull();
    }
}
