package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Editing a trip, which is mostly a test about its dates.
 *
 * Days are derived from the range and places carry a plain date, so nothing in
 * the database stops a place referring to a day the trip no longer has. These
 * pin the two behaviours that keep that from happening quietly: a move of the
 * same length carries the itinerary with it, and anything else that would strand
 * content is refused rather than hiding or deleting it.
 */
class TripUpdateIntegrationTest extends IntegrationTestBase {

    private Object tripFor(Session owner, String start, String end) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Kyoto","destination":"Kyoto","startDate":"%s","endDate":"%s"}
                """.formatted(start, end)).getBody()).get("id");
    }

    private void addPlace(Session owner, Object tripId, String day, String name) {
        assertThat(post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"%s","name":"%s"}
                """.formatted(day, name)).getStatusCode().value()).isEqualTo(201);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> daysOf(Session caller, Object tripId) {
        return (List<Map<String, Object>>) asMap(
                get(caller, "/api/trips/" + tripId + "/itinerary").getBody()).get("days");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> placesOf(Map<String, Object> day) {
        return (List<Map<String, Object>>) day.get("places");
    }

    @Test
    void nameAndDestinationAreRewritten() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-19");

        var response = put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto and Osaka","destination":"Kansai","startDate":"2027-07-12",
                 "endDate":"2027-07-19"}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(response.getBody()))
                .containsEntry("name", "Kyoto and Osaka")
                .containsEntry("destination", "Kansai")
                .containsEntry("dayCount", 8);

        // A blank destination clears it rather than being rejected.
        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto and Osaka","startDate":"2027-07-12","endDate":"2027-07-19"}
                """).getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(get(owner, "/api/trips/" + tripId).getBody()).get("destination")).isNull();
    }

    @Test
    void movingATripOfTheSameLengthCarriesTheItineraryWithIt() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-15");
        addPlace(owner, tripId, "2027-07-12", "Fushimi Inari");
        addPlace(owner, tripId, "2027-07-15", "Nishiki Market");
        assertThat(put(owner, "/api/trips/" + tripId + "/days/2027-07-13/note", """
                {"note":"Train at nine"}
                """).getStatusCode().value()).isEqualTo(200);

        // A week later, same length. Nothing may be lost: this is the edit
        // somebody makes when the flights change.
        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","destination":"Kyoto","startDate":"2027-07-19",
                 "endDate":"2027-07-22"}
                """).getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> days = daysOf(owner, tripId);
        assertThat(days).hasSize(4);
        assertThat(days.get(0)).containsEntry("date", "2027-07-19");
        // Both places moved by the same seven days, keeping the day they were on.
        assertThat(placesOf(days.get(0))).singleElement()
                .satisfies(place -> assertThat(place).containsEntry("name", "Fushimi Inari"));
        assertThat(placesOf(days.get(3))).singleElement()
                .satisfies(place -> assertThat(place).containsEntry("name", "Nishiki Market"));
        // And so did the note.
        assertThat(days.get(1)).containsEntry("note", "Train at nine");
        assertThat(days.get(0).get("note")).isNull();
    }

    @Test
    void severalNotesShiftWithoutCollidingOnTheWay() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-15");
        // Consecutive days: shifting by one moves the 12th onto the 13th while the
        // 13th is still occupied, which is exactly what the unique constraint on
        // (trip_id, day_date) would refuse if the rows were updated in place.
        for (String day : List.of("2027-07-12", "2027-07-13", "2027-07-14", "2027-07-15")) {
            assertThat(put(owner, "/api/trips/" + tripId + "/days/" + day + "/note", """
                    {"note":"note for %s"}
                    """.formatted(day)).getStatusCode().value()).isEqualTo(200);
        }

        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-13","endDate":"2027-07-16"}
                """).getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> days = daysOf(owner, tripId);
        assertThat(days.get(0)).containsEntry("date", "2027-07-13")
                .containsEntry("note", "note for 2027-07-12");
        assertThat(days.get(3)).containsEntry("date", "2027-07-16")
                .containsEntry("note", "note for 2027-07-15");
    }

    @Test
    void shorteningATripOverContentIsRefusedAndChangesNothing() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-19");
        addPlace(owner, tripId, "2027-07-18", "Arashiyama");
        addPlace(owner, tripId, "2027-07-19", "Nishiki Market");
        put(owner, "/api/trips/" + tripId + "/days/2027-07-19/note", """
                {"note":"Late flight"}
                """);

        var refused = put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-12","endDate":"2027-07-15"}
                """);
        // 409, and the message says what is in the way — the count is what tells
        // somebody whether they are about to lose an afternoon's planning.
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat((String) asMap(refused.getBody()).get("message"))
                .contains("2 places").contains("1 note").contains("2027-07-15")
                // Plural subject, plural verb.
                .contains("fall outside");

        // Nothing moved: the trip is as it was, and so is its itinerary.
        assertThat(asMap(get(owner, "/api/trips/" + tripId).getBody()))
                .containsEntry("endDate", "2027-07-19").containsEntry("dayCount", 8);
        assertThat(daysOf(owner, tripId)).hasSize(8);
    }

    @Test
    void oneStrandedPlaceIsDescribedInTheSingular() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-19");
        addPlace(owner, tripId, "2027-07-19", "Nishiki Market");

        var refused = put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-12","endDate":"2027-07-15"}
                """);

        assertThat((String) asMap(refused.getBody()).get("message"))
                .isEqualTo("1 place falls outside 2027-07-12 to 2027-07-15. Move or delete it first.");
    }

    @Test
    void shorteningATripOverEmptyDaysIsFine() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-19");
        addPlace(owner, tripId, "2027-07-13", "Gion");

        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-12","endDate":"2027-07-15"}
                """).getStatusCode().value()).isEqualTo(200);
        assertThat(daysOf(owner, tripId)).hasSize(4);
    }

    @Test
    void growingATripIsAlwaysFine() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-15");
        addPlace(owner, tripId, "2027-07-12", "Fushimi Inari");

        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-10","endDate":"2027-07-20"}
                """).getStatusCode().value()).isEqualTo(200);

        List<Map<String, Object>> days = daysOf(owner, tripId);
        assertThat(days).hasSize(11);
        // The place kept its own date rather than being dragged along: the length
        // changed, so there is no offset to apply.
        assertThat(placesOf(days.get(2))).singleElement()
                .satisfies(place -> assertThat(place).containsEntry("name", "Fushimi Inari"));
    }

    @Test
    void onlyTheOwnerMayEditATrip() {
        Session owner = register("owner");
        Session editor = register("editor");
        Session stranger = register("stranger");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-19");
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"EDITOR"}
                """.formatted(editor.email()));

        String body = """
                {"name":"Renamed","startDate":"2027-07-12","endDate":"2027-07-19"}
                """;
        // An editor changes content, not the trip itself.
        assertThat(put(editor, "/api/trips/" + tripId, body).getStatusCode().value()).isEqualTo(403);
        // A stranger does not learn it exists.
        assertThat(put(stranger, "/api/trips/" + tripId, body).getStatusCode().value()).isEqualTo(404);
        assertThat(put(owner, "/api/trips/" + tripId, body).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void backwardsDatesAreRejected() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-19");

        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-19","endDate":"2027-07-12"}
                """).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void theCurrencyMovesOnlyWhileNothingHasBeenCountedInIt() {
        Session owner = register("owner");
        Object tripId = tripFor(owner, "2027-07-12", "2027-07-19");
        Object ownerId = asList(get(owner, "/api/trips/" + tripId + "/members").getBody())
                .get(0).get("userId");

        // A mistake caught straight after creating the trip: still fixable.
        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-12","endDate":"2027-07-19","currency":"JPY"}
                """).getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(get(owner, "/api/trips/" + tripId).getBody())).containsEntry("currency", "JPY");

        post(owner, "/api/trips/" + tripId + "/expenses", """
                {"description":"Shinkansen","amountMinor":13000,"spentOn":"2027-07-12",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(ownerId, ownerId));

        // Now 13000 means something. Relabelling it as euros would be a lie.
        var refused = put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto","startDate":"2027-07-12","endDate":"2027-07-19","currency":"EUR"}
                """);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat((String) asMap(refused.getBody()).get("message")).contains("JPY");

        // The same currency is not a change, so an ordinary rename still works.
        assertThat(put(owner, "/api/trips/" + tripId, """
                {"name":"Kyoto again","startDate":"2027-07-12","endDate":"2027-07-19","currency":"JPY"}
                """).getStatusCode().value()).isEqualTo(200);
    }
}
