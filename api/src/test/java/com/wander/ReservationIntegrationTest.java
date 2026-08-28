package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Bookings, and the clock they are kept on.
 *
 * This is the first thing in the project with a time rather than a date, so most
 * of these are about zones: a stored instant that means the same moment
 * everywhere, a wall-clock time that comes back saying what the ticket said, and
 * an ordering that stays right when a trip crosses a timezone.
 */
class ReservationIntegrationTest extends IntegrationTestBase {

    private Object tripFor(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Japan","startDate":"2027-07-12","endDate":"2027-07-20"}
                """).getBody()).get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("reservations");
    }

    private List<Map<String, Object>> reservationsOf(Session caller, Object tripId) {
        return listOf(asMap(get(caller, "/api/trips/" + tripId + "/reservations").getBody()));
    }

    @Test
    void aFlightKeepsBothItsClocks() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        // Leaves London at 09:15, lands in Osaka at 07:40 the next morning. Both
        // are what the ticket says; neither is the other's zone.
        var created = post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"FLIGHT","title":"BA512 to Osaka","confirmation":"XK29PQ",
                 "startsAtLocal":"2027-07-12T09:15:00","startZone":"Europe/London",
                 "endsAtLocal":"2027-07-13T07:40:00","endZone":"Asia/Tokyo"}
                """);
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        Map<String, Object> booking = asMap(created.getBody());
        // The wall clock comes back exactly as entered — that is what people read.
        assertThat(booking).containsEntry("startsAtLocal", "2027-07-12T09:15:00");
        assertThat(booking).containsEntry("endsAtLocal", "2027-07-13T07:40:00");
        assertThat(booking).containsEntry("startZone", "Europe/London");
        assertThat(booking).containsEntry("endZone", "Asia/Tokyo");
        // And the instant is the same moment expressed in UTC: London is +1 in July.
        assertThat((String) booking.get("startsAt")).startsWith("2027-07-12T08:15");
        // Tokyo is +9 all year.
        assertThat((String) booking.get("endsAt")).startsWith("2027-07-12T22:40");
    }

    @Test
    void theEndZoneDefaultsToTheStartZone() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        // A hotel does not move, so repeating the zone would be a field to get
        // wrong for nothing.
        var created = post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"HOTEL","title":"Hotel Granvia",
                 "startsAtLocal":"2027-07-13T15:00:00","startZone":"Asia/Tokyo",
                 "endsAtLocal":"2027-07-16T10:00:00"}
                """);

        assertThat(asMap(created.getBody())).containsEntry("endZone", "Asia/Tokyo")
                .containsEntry("endsAtLocal", "2027-07-16T10:00:00");
    }

    @Test
    void abookingWithNoEndIsFine() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        var created = post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"RESTAURANT","title":"Kikunoi",
                 "startsAtLocal":"2027-07-14T19:00:00","startZone":"Asia/Tokyo"}
                """);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> booking = asMap(created.getBody());
        assertThat(booking.get("endsAt")).isNull();
        assertThat(booking.get("endZone")).isNull();
        // No confirmation number either, which is normal for a table.
        assertThat(booking.get("confirmation")).isNull();
    }

    @Test
    void theOrderIsByInstantNotByWallClock() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        // 23:00 in Tokyo is 14:00 UTC; 08:00 in London the next morning is 07:00
        // UTC the day after — so the Tokyo one really is first, and only the
        // instants say so. Sorting on the local times would put them the wrong
        // way round.
        post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"TRAIN","title":"Later, in London",
                 "startsAtLocal":"2027-07-13T08:00:00","startZone":"Europe/London"}
                """);
        post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"TRAIN","title":"Earlier, in Tokyo",
                 "startsAtLocal":"2027-07-12T23:00:00","startZone":"Asia/Tokyo"}
                """);

        assertThat(reservationsOf(owner, tripId))
                .extracting(booking -> booking.get("title"))
                .containsExactly("Earlier, in Tokyo", "Later, in London");
    }

    @Test
    void anUnknownZoneIsRefused() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        // The one field a client can get wrong in a way that makes the whole
        // record undisplayable, so it is checked rather than trusted.
        assertThat(post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"TRAIN","title":"Nowhere",
                 "startsAtLocal":"2027-07-13T08:00:00","startZone":"Middle/Earth"}
                """).getStatusCode().value()).isEqualTo(400);

        assertThat(post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"FLIGHT","title":"Bad arrival",
                 "startsAtLocal":"2027-07-13T08:00:00","startZone":"Europe/London",
                 "endsAtLocal":"2027-07-13T12:00:00","endZone":"Not/AZone"}
                """).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anEndBeforeItsStartIsRefused() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        assertThat(post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"HOTEL","title":"Backwards",
                 "startsAtLocal":"2027-07-16T15:00:00","startZone":"Asia/Tokyo",
                 "endsAtLocal":"2027-07-13T10:00:00"}
                """).getStatusCode().value()).isEqualTo(400);

        // And across zones, where it is not obvious by eye: 09:00 in Tokyo is
        // before 09:00 in London on the same date, so this one is backwards too.
        assertThat(post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"FLIGHT","title":"Backwards across zones",
                 "startsAtLocal":"2027-07-13T09:00:00","startZone":"Europe/London",
                 "endsAtLocal":"2027-07-13T09:00:00","endZone":"Asia/Tokyo"}
                """).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aBookingIsRewrittenAndDeleted() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        Object id = asMap(post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"TRAIN","title":"Shinkansen","confirmation":"AB1",
                 "startsAtLocal":"2027-07-14T07:40:00","startZone":"Asia/Tokyo"}
                """).getBody()).get("id");

        var updated = put(owner, "/api/trips/" + tripId + "/reservations/" + id, """
                {"kind":"TRAIN","title":"Shinkansen to Kyoto","notes":"car 7",
                 "startsAtLocal":"2027-07-14T08:10:00","startZone":"Asia/Tokyo"}
                """);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(updated.getBody()))
                .containsEntry("title", "Shinkansen to Kyoto")
                .containsEntry("notes", "car 7")
                .containsEntry("startsAtLocal", "2027-07-14T08:10:00");
        // Blanked rather than kept: the whole booking is rewritten.
        assertThat(asMap(updated.getBody()).get("confirmation")).isNull();

        assertThat(delete(owner, "/api/trips/" + tripId + "/reservations/" + id)
                .getStatusCode().value()).isEqualTo(204);
        assertThat(reservationsOf(owner, tripId)).isEmpty();
    }

    @Test
    void aViewerReadsAndAStrangerDoesNot() {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Session stranger = register("stranger");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email()));
        Object id = asMap(post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"HOTEL","title":"Hotel Granvia",
                 "startsAtLocal":"2027-07-13T15:00:00","startZone":"Asia/Tokyo"}
                """).getBody()).get("id");

        assertThat(reservationsOf(viewer, tripId)).hasSize(1);
        assertThat(post(viewer, "/api/trips/" + tripId + "/reservations", """
                {"kind":"HOTEL","title":"Sneaky",
                 "startsAtLocal":"2027-07-13T15:00:00","startZone":"Asia/Tokyo"}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(delete(viewer, "/api/trips/" + tripId + "/reservations/" + id)
                .getStatusCode().value()).isEqualTo(403);

        assertThat(get(stranger, "/api/trips/" + tripId + "/reservations").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void aBookingOutsideTheTripsDatesIsAllowed() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        // Like an expense, and unlike a place: the airport hotel the night before
        // is a real booking for this trip.
        assertThat(post(owner, "/api/trips/" + tripId + "/reservations", """
                {"kind":"HOTEL","title":"Airport hotel, night before",
                 "startsAtLocal":"2027-07-11T21:00:00","startZone":"Europe/London"}
                """).getStatusCode().value()).isEqualTo(201);
    }
}
