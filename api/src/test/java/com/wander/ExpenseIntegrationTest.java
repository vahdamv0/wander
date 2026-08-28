package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Expenses over HTTP: the split arithmetic as the client actually receives it,
 * the validation that keeps a balance trustworthy, and who is allowed to write.
 *
 * The arithmetic itself is covered exhaustively in
 * {@link com.wander.expense.ExpenseSplitterTest}; what these add is that it
 * survives the round trip through Postgres and JSON, and that a request which
 * would corrupt a balance is refused rather than adjusted.
 */
class ExpenseIntegrationTest extends IntegrationTestBase {

    private Object tripFor(Session owner, String currency) {
        var created = post(owner, "/api/trips", """
                {"name":"Split test","startDate":"2027-05-01","endDate":"2027-05-04","currency":"%s"}
                """.formatted(currency));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> trip = asMap(created.getBody());
        assertThat(trip).containsEntry("currency", currency);
        return trip.get("id");
    }

    private Object addMember(Session owner, Object tripId, Session invitee, String role) {
        assertThat(post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"%s"}
                """.formatted(invitee.email(), role)).getStatusCode().value()).isEqualTo(201);
        return userIdOf(owner, tripId, invitee);
    }

    private Object userIdOf(Session caller, Object tripId, Session member) {
        return asList(get(caller, "/api/trips/" + tripId + "/members").getBody()).stream()
                .filter(row -> member.email().equals(row.get("email")))
                .findFirst().orElseThrow().get("userId");
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    @Test
    void anUnevenEqualSplitAddsUpToThePenny() {
        Session alice = register("alice");
        Session bob = register("bob");
        Session cara = register("cara");
        Object tripId = tripFor(alice, "EUR");
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        Object caraId = addMember(alice, tripId, cara, "EDITOR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // 10.00 three ways does not divide. The point is that nothing is lost.
        var created = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Coffee","amountMinor":1000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL",
                 "shares":[{"userId":%s},{"userId":%s},{"userId":%s}]}
                """.formatted(aliceId, aliceId, bobId, caraId));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        List<Map<String, Object>> shares = listOf(asMap(created.getBody()), "shares");
        assertThat(shares).hasSize(3);
        assertThat(shares.stream().mapToLong(share -> asLong(share.get("amountMinor"))).sum())
                .as("the shares sum to the expense exactly")
                .isEqualTo(1000L);
        // 334/333/333, and the extra minor unit lands deterministically.
        assertThat(shares.stream().map(share -> asLong(share.get("amountMinor"))).sorted().toList())
                .containsExactly(333L, 333L, 334L);
    }

    @Test
    void balancesNetToZeroAndNameTheDebtor() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice, "EUR");
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // Alice pays 90 for both; Bob pays 20 for both.
        post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":9000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s},{"userId":%s}]}
                """.formatted(aliceId, aliceId, bobId));
        post(bob, "/api/trips/" + tripId + "/expenses", """
                {"description":"Taxi","amountMinor":2000,"spentOn":"2027-05-02",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s},{"userId":%s}]}
                """.formatted(bobId, aliceId, bobId));

        Map<String, Object> body = asMap(get(alice, "/api/trips/" + tripId + "/expenses").getBody());
        Map<String, Object> summary = mapOf(body, "summary");

        assertThat(asLong(summary.get("totalMinor"))).isEqualTo(11000L);
        assertThat(listOf(body, "expenses")).hasSize(2);

        List<Map<String, Object>> balances = listOf(summary, "balances");
        assertThat(balances.stream().mapToLong(row -> asLong(row.get("netMinor"))).sum())
                .as("every balance sums to zero, always")
                .isZero();

        // Alice is out 90 and owes 55 of it; Bob is out 20 and owes 55.
        Map<String, Object> aliceRow = balances.stream()
                .filter(row -> asLong(row.get("userId")) == asLong(aliceId)).findFirst().orElseThrow();
        assertThat(asLong(aliceRow.get("paidMinor"))).isEqualTo(9000L);
        assertThat(asLong(aliceRow.get("shareMinor"))).isEqualTo(5500L);
        assertThat(asLong(aliceRow.get("netMinor"))).isEqualTo(3500L);
        assertThat(aliceRow).containsEntry("stillAMember", true);

        // One transfer clears it, in the right direction.
        List<Map<String, Object>> settlements = listOf(summary, "settlements");
        assertThat(settlements).hasSize(1);
        assertThat(asLong(settlements.get(0).get("fromUserId"))).isEqualTo(asLong(bobId));
        assertThat(asLong(settlements.get(0).get("toUserId"))).isEqualTo(asLong(aliceId));
        assertThat(asLong(settlements.get(0).get("amountMinor"))).isEqualTo(3500L);
    }

    @Test
    void anExactSplitMustAddUp() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice, "EUR");
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // 30 + 29 for a 60 bill: refused, not quietly adjusted. Swallowing this
        // would corrupt every balance on the trip with no error anywhere.
        var wrong = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Groceries","amountMinor":6000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EXACT",
                 "shares":[{"userId":%s,"amountMinor":3000},{"userId":%s,"amountMinor":2900}]}
                """.formatted(aliceId, aliceId, bobId));
        assertThat(wrong.getStatusCode().value()).isEqualTo(400);

        // An exact split that does add up, including a zero share.
        var right = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Museum","amountMinor":6000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EXACT",
                 "shares":[{"userId":%s,"amountMinor":6000},{"userId":%s,"amountMinor":0}]}
                """.formatted(aliceId, aliceId, bobId));
        assertThat(right.getStatusCode().value()).isEqualTo(201);
        assertThat(asMap(right.getBody())).containsEntry("splitMode", "EXACT");

        // And one with an amount missing altogether.
        var incomplete = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Vague","amountMinor":6000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EXACT",
                 "shares":[{"userId":%s,"amountMinor":6000},{"userId":%s}]}
                """.formatted(aliceId, aliceId, bobId));
        assertThat(incomplete.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void nonsenseIsRefused() {
        Session alice = register("alice");
        Session stranger = register("stranger");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);
        Object strangerId = 999999;

        // Zero and negative amounts.
        for (String amount : List.of("0", "-500")) {
            assertThat(post(alice, "/api/trips/" + tripId + "/expenses", """
                    {"description":"Nothing","amountMinor":%s,"spentOn":"2027-05-01",
                     "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                    """.formatted(amount, aliceId, aliceId)).getStatusCode().value())
                    .as("amountMinor %s", amount).isEqualTo(400);
        }

        // Nobody in the split.
        assertThat(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Alone","amountMinor":500,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[]}
                """.formatted(aliceId)).getStatusCode().value()).isEqualTo(400);

        // A payer who is not on the trip: 400, not 404 — the id is request data,
        // and a 404 would say whether that user exists.
        assertThat(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Ghost","amountMinor":500,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(strangerId, aliceId)).getStatusCode().value()).isEqualTo(400);

        // A participant who is not on the trip.
        assertThat(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Ghost share","amountMinor":500,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s},{"userId":%s}]}
                """.formatted(aliceId, aliceId, strangerId)).getStatusCode().value()).isEqualTo(400);

        // The same person twice.
        assertThat(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Double","amountMinor":500,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s},{"userId":%s}]}
                """.formatted(aliceId, aliceId, aliceId)).getStatusCode().value()).isEqualTo(400);

        // And a stranger cannot see the ledger at all.
        assertThat(get(stranger, "/api/trips/" + tripId + "/expenses").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void aDateOutsideTheTripIsFine() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // The flight is paid months before the trip. Unlike a place's day, this is
        // deliberately not range-checked — refusing it would only teach people to
        // enter a false date.
        assertThat(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Flights","amountMinor":42000,"spentOn":"2027-01-06",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void aViewerReadsTheLedgerButWritesNothing() {
        Session alice = register("alice");
        Session viewer = register("viewer");
        Object tripId = tripFor(alice, "EUR");
        Object viewerId = addMember(alice, tripId, viewer, "VIEWER");
        Object aliceId = userIdOf(alice, tripId, alice);

        assertThat(get(viewer, "/api/trips/" + tripId + "/expenses").getStatusCode().value()).isEqualTo(200);
        assertThat(post(viewer, "/api/trips/" + tripId + "/expenses", """
                {"description":"Sneaky","amountMinor":500,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(viewerId, viewerId)).getStatusCode().value()).isEqualTo(403);

        // A viewer with no expenses still appears in the balances, at zero: an
        // absent row would read as missing data rather than as nothing owed.
        post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":4000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId));
        Map<String, Object> summary = mapOf(
                asMap(get(viewer, "/api/trips/" + tripId + "/expenses").getBody()), "summary");
        assertThat(listOf(summary, "balances")).hasSize(2);
        assertThat(listOf(summary, "balances").stream()
                .filter(row -> asLong(row.get("userId")) == asLong(viewerId))
                .findFirst().orElseThrow())
                .containsEntry("netMinor", 0);
    }

    @Test
    void anExpenseIsRewrittenWholeAndDeletedCleanly() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice, "EUR");
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        Object aliceId = userIdOf(alice, tripId, alice);

        Object expenseId = asMap(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Hotel","amountMinor":30000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s},{"userId":%s}]}
                """.formatted(aliceId, aliceId, bobId)).getBody()).get("id");

        // Rewritten: fewer participants, different payer, different amount.
        var updated = put(alice, "/api/trips/" + tripId + "/expenses/" + expenseId, """
                {"description":"Hotel, one night","amountMinor":15000,"spentOn":"2027-05-02",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(bobId, bobId));
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = asMap(updated.getBody());
        assertThat(body).containsEntry("description", "Hotel, one night");
        // The old split is gone rather than added to.
        assertThat(listOf(body, "shares")).hasSize(1);

        assertThat(delete(alice, "/api/trips/" + tripId + "/expenses/" + expenseId)
                .getStatusCode().value()).isEqualTo(204);

        Map<String, Object> after = asMap(get(alice, "/api/trips/" + tripId + "/expenses").getBody());
        assertThat(listOf(after, "expenses")).isEmpty();
        assertThat(asLong(mapOf(after, "summary").get("totalMinor"))).isZero();
        // And the shares went with it, so nobody is left owing for a deleted bill.
        assertThat(listOf(mapOf(after, "summary"), "balances"))
                .allSatisfy(row -> assertThat(asLong(row.get("netMinor"))).isZero());
    }

    @Test
    void somebodyWhoLeftTheTripStillAppearsInTheBalances() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice, "EUR");
        Object bobId = addMember(alice, tripId, bob, "EDITOR");
        Object aliceId = userIdOf(alice, tripId, alice);

        post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":5000,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s},{"userId":%s}]}
                """.formatted(aliceId, aliceId, bobId));

        // Bob leaves owing 25. His shares stay: money is history, membership is
        // present tense, and dropping the row would silently forgive the debt.
        assertThat(delete(bob, "/api/trips/" + tripId + "/members/" + bobId).getStatusCode().value())
                .isEqualTo(204);

        Map<String, Object> summary = mapOf(
                asMap(get(alice, "/api/trips/" + tripId + "/expenses").getBody()), "summary");
        Map<String, Object> bobRow = listOf(summary, "balances").stream()
                .filter(row -> asLong(row.get("userId")) == asLong(bobId))
                .findFirst().orElseThrow();

        assertThat(bobRow).containsEntry("stillAMember", false);
        assertThat(asLong(bobRow.get("netMinor"))).isEqualTo(-2500L);
        assertThat(listOf(summary, "balances").stream()
                .mapToLong(row -> asLong(row.get("netMinor"))).sum()).isZero();
    }

    @Test
    void aTripTakesTheInstanceCurrencyWhenNoneIsGiven() {
        Session alice = register("alice");

        Map<String, Object> trip = asMap(post(alice, "/api/trips", """
                {"name":"No opinion","startDate":"2027-05-01","endDate":"2027-05-02"}
                """).getBody());

        assertThat(trip).containsEntry("currency", "EUR");
    }

    @Test
    void aCurrencyThatIsNotAThreeLetterCodeIsRejected() {
        Session alice = register("alice");

        assertThat(post(alice, "/api/trips", """
                {"name":"Bad money","startDate":"2027-05-01","endDate":"2027-05-02","currency":"euro"}
                """).getStatusCode().value()).isEqualTo(400);
    }
}
