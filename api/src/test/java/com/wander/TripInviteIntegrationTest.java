package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/**
 * Invitation links — the way somebody with no account joins a trip.
 *
 * The interesting assertions are all about a link being a *credential*: it works
 * once, it stops working when revoked, it stops working when it expires, and
 * everything about it that could tell a stranger something is a 404 instead.
 */
class TripInviteIntegrationTest extends IntegrationTestBase {

    private Object tripFor(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Lisbon","startDate":"2027-04-01","endDate":"2027-04-04","currency":"EUR"}
                """).getBody()).get("id");
    }

    private Map<String, Object> createInvite(Session owner, Object tripId, String role) {
        return asMap(post(owner, "/api/trips/" + tripId + "/invites", """
                {"role":"%s"}
                """.formatted(role)).getBody());
    }

    @Test
    void aLinkLetsSomebodyJoinOnceAndThenIsSpent() {
        Session owner = register("owner");
        Session guest = register("guest");
        Object tripId = tripFor(owner);

        Map<String, Object> created = createInvite(owner, tripId, "EDITOR");
        String token = (String) created.get("token");
        assertThat(token).as("the token is returned exactly once, here").isNotBlank();
        // The client builds the URL from its own origin; the server only knows
        // the path, because behind a proxy it sees an internal host.
        assertThat(created.get("path")).isEqualTo("/invite/" + token);

        // The guest can see what they are being offered before committing.
        Map<String, Object> preview = asMap(get(guest, "/api/invites/" + token).getBody());
        assertThat(preview.get("tripName")).isEqualTo("Lisbon");
        assertThat(preview.get("role")).isEqualTo("EDITOR");
        assertThat(preview.get("joinable")).isEqualTo(true);
        // Not the itinerary: they are not a member yet.
        assertThat(preview).doesNotContainKey("days");

        assertThat(asMap(post(guest, "/api/invites/" + token + "/accept", "").getBody()).get("tripId"))
                .isEqualTo(tripId);
        // On the trip, with the role the link carried.
        assertThat(asList(get(owner, "/api/trips/" + tripId + "/members").getBody()))
                .anySatisfy(member -> {
                    assertThat(member.get("email")).isEqualTo(guest.email());
                    assertThat(member.get("role")).isEqualTo("EDITOR");
                });

        // Spent. A forwarded link does not admit a second person.
        Session stranger = register("stranger");
        assertThat(get(stranger, "/api/invites/" + token).getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(get(stranger, "/api/invites/" + token).getBody()).get("joinable")).isEqualTo(false);
        assertThat(post(stranger, "/api/invites/" + token + "/accept", "").getStatusCode().value())
                .isEqualTo(409);
        assertThat(asList(get(owner, "/api/trips/" + tripId + "/members").getBody())).hasSize(2);
    }

    @Test
    void aRevokedLinkNoLongerWorks() {
        Session owner = register("owner");
        Session guest = register("guest");
        Object tripId = tripFor(owner);

        Map<String, Object> created = createInvite(owner, tripId, "VIEWER");
        String token = (String) created.get("token");
        Object inviteId = inviteIdOf(created);

        assertThat(delete(owner, "/api/trips/" + tripId + "/invites/" + inviteId)
                .getStatusCode().value()).isEqualTo(204);

        assertThat(asMap(get(guest, "/api/invites/" + token).getBody()).get("joinable")).isEqualTo(false);
        assertThat(post(guest, "/api/invites/" + token + "/accept", "").getStatusCode().value())
                .isEqualTo(409);

        // And the owner can see what became of it rather than the row vanishing.
        assertThat(asList(get(owner, "/api/trips/" + tripId + "/invites").getBody()))
                .singleElement()
                .satisfies(invite -> assertThat(invite.get("status")).isEqualTo("REVOKED"));
    }

    @Test
    void onlyTheOwnerMintsOrListsLinks() {
        Session owner = register("owner");
        Session editor = register("editor");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"EDITOR"}
                """.formatted(editor.email()));

        // A member with too weak a role: 403, because they already know the trip exists.
        assertThat(post(editor, "/api/trips/" + tripId + "/invites", """
                {"role":"VIEWER"}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(get(editor, "/api/trips/" + tripId + "/invites").getStatusCode().value()).isEqualTo(403);

        // A non-member: 404, and never learns the trip is real.
        Session outsider = register("outsider");
        assertThat(post(outsider, "/api/trips/" + tripId + "/invites", """
                {"role":"VIEWER"}
                """).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aLinkCannotGrantOwnership() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        assertThat(post(owner, "/api/trips/" + tripId + "/invites", """
                {"role":"OWNER"}
                """).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anUnknownTokenIsAlwaysA404() {
        Session guest = register("guest");
        // Not "expired", not "revoked" — nothing that tells a guesser their
        // attempt found something real.
        assertThat(get(guest, "/api/invites/there-is-no-such-token").getStatusCode().value()).isEqualTo(404);
        assertThat(post(guest, "/api/invites/there-is-no-such-token/accept", "").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void acceptingYourOwnTripChangesNothing() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        String token = (String) createInvite(owner, tripId, "EDITOR").get("token");

        Map<String, Object> preview = asMap(get(owner, "/api/invites/" + token).getBody());
        assertThat(preview.get("joinable")).isEqualTo(false);
        assertThat(preview.get("tripId")).isEqualTo(tripId);

        // Accepting is a no-op that still sends them to the trip, rather than an
        // error — and it must not demote the owner to the link's role.
        assertThat(asMap(post(owner, "/api/invites/" + token + "/accept", "").getBody()).get("tripId"))
                .isEqualTo(tripId);
        assertThat(asList(get(owner, "/api/trips/" + tripId + "/members").getBody()))
                .singleElement()
                .satisfies(member -> assertThat(member.get("role")).isEqualTo("OWNER"));
    }

    /**
     * Two people, one link, at the same moment.
     *
     * This is the assertion the pessimistic lock in
     * `TripInviteRepository.findByTokenHashForUpdate` exists for. Without it both
     * transactions read an unused invitation and both add a member, so a
     * single-use link admits two strangers — and nothing else in the suite would
     * notice, because each request on its own looks perfectly correct.
     */
    @Test
    void twoPeopleRacingForOneLinkGetOneMembership() throws Exception {
        Session owner = register("owner");
        Session first = register("first");
        Session second = register("second");
        Object tripId = tripFor(owner);
        String token = (String) createInvite(owner, tripId, "EDITOR").get("token");

        List<Callable<Integer>> both = List.of(
                () -> post(first, "/api/invites/" + token + "/accept", "").getStatusCode().value(),
                () -> post(second, "/api/invites/" + token + "/accept", "").getStatusCode().value());

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            statuses = pool.invokeAll(both).stream().map(TripInviteIntegrationTest::value).sorted().toList();
        }

        // One joins, the other is told the link is spent.
        assertThat(statuses).containsExactly(200, 409);
        assertThat(asList(get(owner, "/api/trips/" + tripId + "/members").getBody())).hasSize(2);
    }

    /** The created response nests the view under `invite`, beside the one-time token. */
    @SuppressWarnings("unchecked")
    private static Object inviteIdOf(Map<String, Object> created) {
        return ((Map<String, Object>) created.get("invite")).get("id");
    }

    private static Integer value(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
