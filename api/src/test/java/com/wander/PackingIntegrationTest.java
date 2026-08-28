package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The packing list: who an item belongs to, who ticked it, and who may do either.
 *
 * The grouping is the part worth testing hard. It is the first thing in this
 * project that belongs to a *person* on a trip rather than to the trip, so "whose
 * is it" has answers the rest of the codebase never needed — nobody's, somebody
 * who is still here, and somebody who has since left.
 */
class PackingIntegrationTest extends IntegrationTestBase {

    private Object tripFor(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Norway","startDate":"2027-06-01","endDate":"2027-06-05"}
                """).getBody()).get("id");
    }

    private Object addMember(Session owner, Object tripId, Session invitee, String role) {
        assertThat(post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"%s"}
                """.formatted(invitee.email(), role)).getStatusCode().value()).isEqualTo(201);
        return asList(get(owner, "/api/trips/" + tripId + "/members").getBody()).stream()
                .filter(row -> invitee.email().equals(row.get("email")))
                .findFirst().orElseThrow().get("userId");
    }

    private Object addItem(Session caller, Object tripId, String description, Object assignee) {
        var response = post(caller, "/api/trips/" + tripId + "/packing", """
                {"description":"%s","assigneeUserId":%s}
                """.formatted(description, assignee == null ? "null" : assignee));
        assertThat(response.getStatusCode().value()).as("adding %s", description).isEqualTo(201);
        return asMap(response.getBody()).get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groupsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("groups");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> group) {
        return (List<Map<String, Object>>) group.get("items");
    }

    private static Map<String, Object> groupFor(Map<String, Object> body, Object userId) {
        return groupsOf(body).stream()
                .filter(group -> userId == null ? group.get("userId") == null
                        : String.valueOf(userId).equals(String.valueOf(group.get("userId"))))
                .findFirst().orElseThrow();
    }

    @Test
    void itemsLandInTheSharedPileOrOnAPerson() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice);
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        Object aliceId = groupsOf(asMap(get(alice, "/api/trips/" + tripId + "/packing").getBody()))
                .get(1).get("userId");

        addItem(alice, tripId, "Tent", null);
        addItem(alice, tripId, "Passport", aliceId);
        addItem(alice, tripId, "Chargers", bobId);

        Map<String, Object> body = asMap(get(bob, "/api/trips/" + tripId + "/packing").getBody());
        assertThat(body).containsEntry("totalCount", 3).containsEntry("packedCount", 0);

        // Shared first, then the members in joining order.
        List<Map<String, Object>> groups = groupsOf(body);
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).get("userId")).isNull();
        assertThat(itemsOf(groups.get(0))).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("description", "Tent"));
        assertThat(itemsOf(groupFor(body, aliceId))).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("description", "Passport"));
        assertThat(itemsOf(groupFor(body, bobId))).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("description", "Chargers"));
    }

    @Test
    void everyMemberGetsASectionEvenWithNothingInIt() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice);
        Object bobId = addMember(alice, tripId, bob, "EDITOR");

        Map<String, Object> body = asMap(get(alice, "/api/trips/" + tripId + "/packing").getBody());

        // An absent section reads as missing data; an empty one reads as empty.
        assertThat(groupsOf(body)).hasSize(3);
        assertThat(itemsOf(groupFor(body, bobId))).isEmpty();
        assertThat(groupFor(body, bobId)).containsEntry("packedCount", 0);
        assertThat(body).containsEntry("totalCount", 0);
    }

    @Test
    void tickingRecordsWhoDidItAndUntickingForgets() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice);
        addMember(alice, tripId, bob, "EDITOR");
        Object tent = addItem(alice, tripId, "Tent", null);

        // Bob packs the shared tent. "Packed" alone would not say whose bag it is in.
        var ticked = put(bob, "/api/trips/" + tripId + "/packing/" + tent + "/packed", """
                {"packed":true}
                """);
        assertThat(ticked.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(ticked.getBody())).containsEntry("packed", true)
                .containsEntry("packedByName", "bob");

        Map<String, Object> body = asMap(get(alice, "/api/trips/" + tripId + "/packing").getBody());
        assertThat(body).containsEntry("packedCount", 1);
        assertThat(groupFor(body, null)).containsEntry("packedCount", 1);

        var unticked = put(alice, "/api/trips/" + tripId + "/packing/" + tent + "/packed", """
                {"packed":false}
                """);
        // Cleared rather than left behind: it must not describe a past state.
        assertThat(asMap(unticked.getBody())).containsEntry("packed", false);
        assertThat(asMap(unticked.getBody()).get("packedByName")).isNull();
    }

    @Test
    void anItemIsRenamedAndReassignedIncludingBackToShared() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice);
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        Object item = addItem(alice, tripId, "Charger", null);

        var assigned = put(alice, "/api/trips/" + tripId + "/packing/" + item, """
                {"description":"Phone charger","assigneeUserId":%s}
                """.formatted(bobId));
        assertThat(assigned.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(assigned.getBody())).containsEntry("description", "Phone charger");
        assertThat(String.valueOf(asMap(assigned.getBody()).get("assigneeUserId")))
                .isEqualTo(String.valueOf(bobId));

        // Null is a value, not an omission: it puts the item back in the shared pile.
        var shared = put(alice, "/api/trips/" + tripId + "/packing/" + item, """
                {"description":"Phone charger","assigneeUserId":null}
                """);
        assertThat(asMap(shared.getBody()).get("assigneeUserId")).isNull();
        assertThat(itemsOf(groupFor(asMap(get(alice, "/api/trips/" + tripId + "/packing").getBody()),
                null))).hasSize(1);
    }

    @Test
    void anAssigneeMustBeOnTheTrip() {
        Session alice = register("alice");
        Session outsider = register("outsider");
        Object tripId = tripFor(alice);
        // The outsider exists but is not a member. 400 rather than 404: the id is
        // request data, and a 404 would answer "does this user exist".
        assertThat(post(alice, "/api/trips/" + tripId + "/packing", """
                {"description":"Skis","assigneeUserId":999999}
                """).getStatusCode().value()).isEqualTo(400);
        assertThat(outsider.email()).isNotBlank();
    }

    @Test
    void aViewerReadsTheListAndChangesNothing() {
        Session alice = register("alice");
        Session viewer = register("viewer");
        Object tripId = tripFor(alice);
        addMember(alice, tripId, viewer, "VIEWER");
        Object item = addItem(alice, tripId, "Tent", null);

        assertThat(get(viewer, "/api/trips/" + tripId + "/packing").getStatusCode().value())
                .isEqualTo(200);
        // Including the tick: packing is trip content, and one rule for writes is
        // worth more than an exception for checkboxes.
        assertThat(put(viewer, "/api/trips/" + tripId + "/packing/" + item + "/packed", """
                {"packed":true}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(post(viewer, "/api/trips/" + tripId + "/packing", """
                {"description":"Sneaky","assigneeUserId":null}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(put(viewer, "/api/trips/" + tripId + "/packing/" + item, """
                {"description":"Renamed","assigneeUserId":null}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(delete(viewer, "/api/trips/" + tripId + "/packing/" + item)
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aNonMemberDoesNotLearnTheListExists() {
        Session alice = register("alice");
        Session stranger = register("stranger");
        Object tripId = tripFor(alice);

        assertThat(get(stranger, "/api/trips/" + tripId + "/packing").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void somebodyWhoLeavesKeepsTheirItemsInALabelledSection() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice);
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        addItem(alice, tripId, "Bob's boots", bobId);

        assertThat(delete(bob, "/api/trips/" + tripId + "/members/" + bobId)
                .getStatusCode().value()).isEqualTo(204);

        Map<String, Object> body = asMap(get(alice, "/api/trips/" + tripId + "/packing").getBody());
        Map<String, Object> bobsGroup = groupFor(body, bobId);
        // Kept, and labelled: quietly reassigning what somebody said they were
        // bringing is worse than a section that says they have left.
        assertThat(bobsGroup).containsEntry("stillAMember", false);
        assertThat(itemsOf(bobsGroup)).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("description", "Bob's boots"));
        assertThat(body).containsEntry("totalCount", 1);
    }

    @Test
    void anItemIsDeletedAndTheCountsFollow() {
        Session alice = register("alice");
        Object tripId = tripFor(alice);
        Object item = addItem(alice, tripId, "Tent", null);
        put(alice, "/api/trips/" + tripId + "/packing/" + item + "/packed", """
                {"packed":true}
                """);

        assertThat(delete(alice, "/api/trips/" + tripId + "/packing/" + item)
                .getStatusCode().value()).isEqualTo(204);

        Map<String, Object> body = asMap(get(alice, "/api/trips/" + tripId + "/packing").getBody());
        assertThat(body).containsEntry("totalCount", 0).containsEntry("packedCount", 0);
    }
}
