package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Day notes: one per day, written whole, and reachable only through a trip the
 * caller is a member of. Reads come with the itinerary — there is no GET.
 */
class DayNoteIntegrationTest extends IntegrationTestBase {

    private Object newTrip(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Porto","startDate":"2027-05-10","endDate":"2027-05-12"}
                """).getBody()).get("id");
    }

    private String notePath(Object tripId, String date) {
        return "/api/trips/" + tripId + "/days/" + date + "/note";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> day(Session session, Object tripId, int index) {
        Map<String, Object> itinerary = asMap(get(session, "/api/trips/" + tripId + "/itinerary").getBody());
        return ((List<Map<String, Object>>) itinerary.get("days")).get(index);
    }

    @Test
    void aNoteIsWrittenReplacedAndClearedAndRidesOnTheItinerary() {
        Session owner = register("nora");
        Object tripId = newTrip(owner);

        // A day with no note carries a null, not a missing key.
        assertThat(day(owner, tripId, 0)).containsEntry("note", null);

        var written = put(owner, notePath(tripId, "2027-05-10"), """
                {"note":"  Arrive late — dinner near the station.  "}
                """);
        assertThat(written.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(written.getBody()))
                .containsEntry("dayDate", "2027-05-10")
                // Stored stripped: the surrounding whitespace is a typing artefact.
                .containsEntry("note", "Arrive late — dinner near the station.");

        assertThat(day(owner, tripId, 0))
                .containsEntry("note", "Arrive late — dinner near the station.");
        // Only the day that was written.
        assertThat(day(owner, tripId, 1)).containsEntry("note", null);

        // A second write replaces rather than adding a second note.
        put(owner, notePath(tripId, "2027-05-10"), """
                {"note":"Changed my mind: Ribeira."}
                """);
        assertThat(day(owner, tripId, 0)).containsEntry("note", "Changed my mind: Ribeira.");

        // Blank clears the day — the same endpoint, no second call to learn.
        var cleared = put(owner, notePath(tripId, "2027-05-10"), """
                {"note":"   "}
                """);
        assertThat(cleared.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(cleared.getBody())).containsEntry("note", null);
        assertThat(day(owner, tripId, 0)).containsEntry("note", null);

        // Clearing a day that has no note is not an error.
        assertThat(put(owner, notePath(tripId, "2027-05-11"), "{}").getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void aDayOutsideTheTripIsRejected() {
        Session owner = register("otto");
        Object tripId = newTrip(owner);

        assertThat(put(owner, notePath(tripId, "2027-05-13"), """
                {"note":"The day after the trip ends."}
                """).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aNonMemberCannotSeeOrWriteANote() {
        Session owner = register("pia");
        Session stranger = register("quinn");
        Object tripId = newTrip(owner);

        put(owner, notePath(tripId, "2027-05-10"), """
                {"note":"Private plans."}
                """);

        // 404, not 403: a 403 would confirm the trip exists.
        assertThat(put(stranger, notePath(tripId, "2027-05-10"), """
                {"note":"Not mine."}
                """).getStatusCode().value()).isEqualTo(404);
        assertThat(get(stranger, "/api/trips/" + tripId + "/itinerary").getStatusCode().value())
                .isEqualTo(404);

        // And the note the owner wrote is untouched.
        assertThat(day(owner, tripId, 0)).containsEntry("note", "Private plans.");
    }
}
