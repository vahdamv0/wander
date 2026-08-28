package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sharing a trip: who can be added, what each role may do, and how ownership
 * moves.
 *
 * The first test here is the one this project could not write until now. Until
 * members could be added, no VIEWER or EDITOR existed, so
 * `TripAccessService.requireRole` had a 403 branch that every write endpoint
 * depends on and nothing had ever taken.
 */
class TripMemberIntegrationTest extends IntegrationTestBase {

    /** A trip whose only member is the caller, and its id. */
    private Object tripFor(Session owner) {
        var created = post(owner, "/api/trips", """
                {"name":"Kyoto","destination":"Kyoto","startDate":"2027-03-28","endDate":"2027-03-30"}
                """);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return asMap(created.getBody()).get("id");
    }

    private int addMember(Session owner, Object tripId, Session invitee, String role) {
        return post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"%s"}
                """.formatted(invitee.email(), role)).getStatusCode().value();
    }

    @Test
    void aViewerReadsTheItineraryButCannotWriteToIt() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);

        assertThat(addMember(owner, tripId, viewer, "VIEWER")).isEqualTo(201);

        // The trip is visible, and the client is told which role it has so it can
        // draw a read-only page.
        var trip = asMap(get(viewer, "/api/trips/" + tripId).getBody());
        assertThat(trip).containsEntry("myRole", "VIEWER");
        assertThat(get(viewer, "/api/trips/" + tripId + "/itinerary").getStatusCode().value()).isEqualTo(200);

        // Every write refuses with 403, not 404: a viewer already knows the trip
        // is there, so there is nothing left to hide.
        assertThat(post(viewer, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-03-28","name":"Fushimi Inari"}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(put(viewer, "/api/trips/" + tripId + "/days/2027-03-28/note", """
                {"note":"Train at nine"}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(delete(viewer, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void anEditorWritesButDoesNotManageMembers() throws Exception {
        Session owner = register("owner");
        Session editor = register("editor");
        Session outsider = register("outsider");
        Object tripId = tripFor(owner);

        assertThat(addMember(owner, tripId, editor, "EDITOR")).isEqualTo(201);

        assertThat(post(editor, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-03-29","name":"Gion"}
                """).getStatusCode().value()).isEqualTo(201);
        assertThat(put(editor, "/api/trips/" + tripId + "/days/2027-03-29/note", """
                {"note":"Dinner late"}
                """).getStatusCode().value()).isEqualTo(200);

        // Managing people is the owner's alone.
        assertThat(addMember(editor, tripId, outsider, "VIEWER")).isEqualTo(403);
        // 403 even for an id that is nobody: the caller is refused before the
        // lookup, so the answer cannot be used to probe who is on the trip.
        assertThat(delete(editor, "/api/trips/" + tripId + "/members/9999").getStatusCode().value())
                .isEqualTo(403);
        assertThat(delete(editor, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void everyMemberSeesTheListAndNonMembersDoNot() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Session stranger = register("stranger");
        Object tripId = tripFor(owner);
        addMember(owner, tripId, viewer, "VIEWER");

        List<Map<String, Object>> members = asList(get(viewer, "/api/trips/" + tripId + "/members").getBody());
        assertThat(members).hasSize(2);
        // Joining order, so the owner — created with the trip — reads first.
        assertThat(members.get(0)).containsEntry("role", "OWNER").containsEntry("email", owner.email());
        assertThat(members.get(1)).containsEntry("role", "VIEWER").containsEntry("email", viewer.email());

        // 404 rather than 403: a stranger must not learn the trip exists.
        assertThat(get(stranger, "/api/trips/" + tripId + "/members").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void addingSomebodyWhoCannotBeAdded() throws Exception {
        Session owner = register("owner");
        Session friend = register("friend");
        Object tripId = tripFor(owner);

        // No account with that address. 404 and not 400: the request was fine.
        assertThat(post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"nobody-here@example.com","role":"EDITOR"}
                """).getStatusCode().value()).isEqualTo(404);

        assertThat(addMember(owner, tripId, friend, "EDITOR")).isEqualTo(201);
        // Twice is a conflict, not a silent re-add that would change their role.
        assertThat(addMember(owner, tripId, friend, "VIEWER")).isEqualTo(409);
        // The owner is already a member of their own trip.
        assertThat(addMember(owner, tripId, owner, "EDITOR")).isEqualTo(409);
        // A second owner is not a thing; transferring is.
        assertThat(addMember(owner, tripId, register("other"), "OWNER")).isEqualTo(400);
    }

    @Test
    void aRoleCanBeChangedAndOwnershipTransferred() throws Exception {
        Session owner = register("owner");
        Session friend = register("friend");
        Object tripId = tripFor(owner);
        addMember(owner, tripId, friend, "VIEWER");

        Object friendId = asList(get(owner, "/api/trips/" + tripId + "/members").getBody()).stream()
                .filter(member -> friend.email().equals(member.get("email")))
                .findFirst().orElseThrow().get("userId");

        // A plain promotion.
        var promoted = put(owner, "/api/trips/" + tripId + "/members/" + friendId + "/role", """
                {"role":"EDITOR"}
                """);
        assertThat(promoted.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(promoted.getBody())).containsEntry("role", "EDITOR");

        // An owner does not demote themselves; they hand the trip over.
        Object ownerId = asList(get(owner, "/api/trips/" + tripId + "/members").getBody()).stream()
                .filter(member -> owner.email().equals(member.get("email")))
                .findFirst().orElseThrow().get("userId");
        assertThat(put(owner, "/api/trips/" + tripId + "/members/" + ownerId + "/role", """
                {"role":"EDITOR"}
                """).getStatusCode().value()).isEqualTo(400);

        // The transfer: both halves at once, so there is never a moment with two
        // owners or none.
        assertThat(put(owner, "/api/trips/" + tripId + "/members/" + friendId + "/role", """
                {"role":"OWNER"}
                """).getStatusCode().value()).isEqualTo(200);

        assertThat(asMap(get(friend, "/api/trips/" + tripId).getBody())).containsEntry("myRole", "OWNER");
        assertThat(asMap(get(owner, "/api/trips/" + tripId).getBody())).containsEntry("myRole", "EDITOR");

        // And the rights moved with it.
        assertThat(delete(owner, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(403);
        assertThat(delete(friend, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void aMemberLeavesButAnOwnerMustTransferFirst() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);
        addMember(owner, tripId, viewer, "VIEWER");

        List<Map<String, Object>> members = asList(get(owner, "/api/trips/" + tripId + "/members").getBody());
        Object ownerId = members.get(0).get("userId");
        Object viewerId = members.get(1).get("userId");

        // A trip with no owner could never be deleted or shared again.
        assertThat(delete(owner, "/api/trips/" + tripId + "/members/" + ownerId).getStatusCode().value())
                .isEqualTo(409);
        // A viewer aiming at the owner is refused for the plainer reason first:
        // they may not remove anybody but themselves.
        assertThat(delete(viewer, "/api/trips/" + tripId + "/members/" + ownerId).getStatusCode().value())
                .isEqualTo(403);

        // A viewer cannot remove anybody but themselves — and once gone, the trip
        // is not theirs to see.
        assertThat(delete(viewer, "/api/trips/" + tripId + "/members/" + viewerId).getStatusCode().value())
                .isEqualTo(204);
        assertThat(get(viewer, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(404);
        assertThat(asList(get(owner, "/api/trips/" + tripId + "/members").getBody())).hasSize(1);
    }

    @Test
    void oneMemberCannotRemoveAnother() throws Exception {
        Session owner = register("owner");
        Session first = register("first");
        Session second = register("second");
        Object tripId = tripFor(owner);
        addMember(owner, tripId, first, "EDITOR");
        addMember(owner, tripId, second, "EDITOR");

        Object secondId = asList(get(owner, "/api/trips/" + tripId + "/members").getBody()).stream()
                .filter(member -> second.email().equals(member.get("email")))
                .findFirst().orElseThrow().get("userId");

        assertThat(delete(first, "/api/trips/" + tripId + "/members/" + secondId).getStatusCode().value())
                .isEqualTo(403);
        assertThat(delete(owner, "/api/trips/" + tripId + "/members/" + secondId).getStatusCode().value())
                .isEqualTo(204);
    }
}
