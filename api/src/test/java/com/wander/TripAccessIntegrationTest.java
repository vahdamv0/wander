package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The multi-tenancy contract: a trip is visible to its members and nobody else,
 * and a non-member gets 404 rather than 403 — a 403 would confirm the trip
 * exists and let anyone count trips by walking ids.
 */
class TripAccessIntegrationTest extends IntegrationTestBase {

    @Test
    void aTripIsInvisibleToNonMembers() throws Exception {
        Session alice = register("alice");
        Session bob = register("bob");

        var created = post(alice, "/api/trips", """
                {"name":"Kyoto in spring","destination":"Kyoto","startDate":"2027-03-28","endDate":"2027-04-05"}
                """);
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        Map<String, Object> trip = asMap(created.getBody());
        assertThat(trip).containsEntry("myRole", "OWNER");
        // Nine inclusive days, derived from the range rather than stored.
        assertThat(trip).containsEntry("dayCount", 9);

        Object tripId = trip.get("id");

        assertThat(get(alice, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(200);
        assertThat(asList(get(alice, "/api/trips").getBody())).hasSize(1);

        // Bob: 404 rather than 403, and the trip is absent from his list.
        assertThat(get(bob, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(404);
        assertThat(asList(get(bob, "/api/trips").getBody())).isEmpty();
    }

    @Test
    void onlyTheOwnerCanDeleteATrip() throws Exception {
        Session erin = register("erin");
        Session frank = register("frank");

        Object tripId = asMap(post(erin, "/api/trips", """
                {"name":"Lisbon","startDate":"2027-06-01","endDate":"2027-06-04"}
                """).getBody()).get("id");

        // A non-member cannot even learn it exists.
        assertThat(delete(frank, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(404);
        assertThat(delete(erin, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(204);
        assertThat(get(erin, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void anonymousCallersGet401() {
        var response = http().get().uri("/api/trips").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void endDateBeforeStartDateIsRejected() {
        Session carol = register("carol");
        var response = post(carol, "/api/trips", """
                {"name":"Backwards","startDate":"2027-05-10","endDate":"2027-05-01"}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aWriteWithoutTheCsrfTokenIsRejected() {
        Session dave = register("dave");
        // Same session cookie, no X-XSRF-TOKEN header: what a cross-site form
        // post would look like.
        var response = http().post().uri("/api/trips")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.COOKIE, dave.cookie())
                .body("""
                        {"name":"Forged","startDate":"2027-05-01","endDate":"2027-05-02"}
                        """)
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
