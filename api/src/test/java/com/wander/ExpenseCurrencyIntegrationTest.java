package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.fx.FxRateClient;

/**
 * Paying for something in a currency that is not the trip's.
 *
 * The rate service is replaced, and not for speed: everything worth testing
 * here is a decision made *around* the upstream, and every one of them is
 * invisible while a real rate is answering. Which day is asked about, how often
 * it is asked at all, what gets stored when it answers, and what happens when it
 * does not.
 *
 * The rates are the real ones for the day, quoted **against the euro**, because
 * that is the only direction this application ever asks in — see
 * {@code FxRateClient}.
 */
class ExpenseCurrencyIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private FxRateClient fxRateClient;

    private static final LocalDate SPENT_ON = LocalDate.of(2027, 5, 1);
    /** One euro bought this many yen. Five significant figures, as published. */
    private static final BigDecimal YEN_PER_EUR = new BigDecimal("179.11");

    /**
     * A day nothing else in this class spends on.
     *
     * `fx_rates` is global and has no TTL, which is the point of it — so a row
     * written by one test is still there for the next, exactly as
     * `place_enrichment` rows are. A test that counts calls to the upstream has
     * to bring its own date or it is really testing which test ran first.
     */
    private static final LocalDate OWN_DAY = LocalDate.of(2027, 6, 15);

    @BeforeEach
    void rates() {
        when(fxRateClient.ratesPerEur(any(), anySet()))
                .thenReturn(new FxRateClient.EurRates(SPENT_ON, Map.of("JPY", YEN_PER_EUR)));
        when(fxRateClient.supportedCurrencies()).thenReturn(java.util.Set.of("EUR", "JPY", "USD"));
    }

    private Object tripFor(Session owner, String currency) {
        var created = post(owner, "/api/trips", """
                {"name":"Japan","startDate":"2027-05-01","endDate":"2027-05-04","currency":"%s"}
                """.formatted(currency));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return asMap(created.getBody()).get("id");
    }

    private Object addMember(Session owner, Object tripId, Session invitee) {
        assertThat(post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"EDITOR"}
                """.formatted(invitee.email())).getStatusCode().value()).isEqualTo(201);
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
    void anExpenseInAnotherCurrencyIsStoredInTheTripsAndKeepsItsReceipt() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // ¥8,000 at 179.11 to the euro is €44.67.
        var created = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":8000,"currency":"JPY","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        Map<String, Object> expense = asMap(created.getBody());
        assertThat(asLong(expense.get("amountMinor")))
                .as("amountMinor is the trip's currency, which is what every total sums")
                .isEqualTo(4467L);
        assertThat(asLong(expense.get("sourceAmountMinor")))
                .as("and the receipt survives, or the ledger cannot be checked against a statement")
                .isEqualTo(8000L);
        assertThat(expense).containsEntry("sourceCurrency", "JPY");
        assertThat(expense).containsEntry("fxManual", false);
        assertThat(expense).containsEntry("fxQuotedOn", "2027-05-01");
        // Sent as a string: a rate has more figures than a JSON number carries
        // honestly, and this is the feature that keeps floats out of the wire.
        assertThat(new BigDecimal((String) expense.get("fxRate")))
                .isEqualByComparingTo(new BigDecimal("0.005583161185863"));
    }

    @Test
    void theSplitIsMadeOfTheConvertedTotalAndAddsUpToIt() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice, "EUR");
        Object bobId = addMember(alice, tripId, bob);
        Object aliceId = userIdOf(alice, tripId, alice);

        // ¥8,000 split ¥5,000 / ¥3,000 — typed in yen, because that is what the
        // bill says. Converting each share on its own gives 2792 and 1675, which
        // is a cent short of the €44.67 the expense is worth. This is the case
        // the whole feature is arranged around.
        var created = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":8000,"currency":"JPY","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EXACT",
                 "shares":[{"userId":%s,"amountMinor":5000},{"userId":%s,"amountMinor":3000}]}
                """.formatted(aliceId, aliceId, bobId));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        Map<String, Object> expense = asMap(created.getBody());
        List<Map<String, Object>> shares = listOf(expense, "shares");
        assertThat(shares.stream().mapToLong(share -> asLong(share.get("amountMinor"))).sum())
                .as("the shares sum to the converted expense exactly")
                .isEqualTo(asLong(expense.get("amountMinor")))
                .isEqualTo(4467L);
    }

    @Test
    void exactSharesAreCheckedInTheCurrencyTheyWereTypedIn() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // Yen that do not add up to the yen total. Refusing this in euros would
        // be arithmetic nobody could follow.
        var refused = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":8000,"currency":"JPY","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EXACT","shares":[{"userId":%s,"amountMinor":7999}]}
                """.formatted(aliceId, aliceId));

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody()).contains("7999").contains("8000");
    }

    @Test
    void theRateIsFrozenAndAnEditDoesNotLookItUpAgain() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        Object expenseId = asMap(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":8000,"currency":"JPY","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId)).getBody()).get("id");

        // The rate moves sharply between the two calls. If the edit re-fetched,
        // renaming an expense would move somebody's balance — which is the
        // failure this test exists for, and it is silent in every other way.
        when(fxRateClient.ratesPerEur(any(), anySet()))
                .thenReturn(new FxRateClient.EurRates(SPENT_ON, Map.of("JPY", new BigDecimal("200.00"))));

        var updated = put(alice, "/api/trips/" + tripId + "/expenses/" + expenseId, """
                {"description":"Dinner in Kyoto","amountMinor":8000,"currency":"JPY","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId));

        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> expense = asMap(updated.getBody());
        assertThat(expense).containsEntry("description", "Dinner in Kyoto");
        assertThat(asLong(expense.get("amountMinor")))
                .as("the rate of the day it was spent, not of the day it was edited")
                .isEqualTo(4467L);
    }

    @Test
    void theCachedRateIsSharedRatherThanRefetchedPerExpense() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        for (int i = 0; i < 3; i++) {
            assertThat(post(alice, "/api/trips/" + tripId + "/expenses", """
                    {"description":"Lunch %s","amountMinor":1200,"currency":"JPY","spentOn":"%s",
                     "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                    """.formatted(i, OWN_DAY, aliceId, aliceId)).getStatusCode().value()).isEqualTo(201);
        }

        // A published rate for a past day is final, not merely fresh, so the
        // second and third expenses have nothing to ask about.
        verify(fxRateClient, org.mockito.Mockito.times(1)).ratesPerEur(eq(OWN_DAY), anySet());
    }

    @Test
    void aDateTheUpstreamHasNoRateForIsRefusedWithSomethingToDoAboutIt() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // A deposit paid for a trip in the future: there is no rate yet, and the
        // upstream says so with an empty answer and a 200.
        when(fxRateClient.ratesPerEur(any(), anySet())).thenReturn(FxRateClient.EurRates.none());

        var refused = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Deposit","amountMinor":50000,"currency":"JPY","spentOn":"2027-05-02",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId));

        // Refused, not stored with no rate — the one upstream in this project
        // whose failure is not allowed to be quiet. And the message has to name
        // the way out, because there is one.
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(refused.getBody()).contains("Enter the rate");

        assertThat(listOf(asMap(get(alice, "/api/trips/" + tripId + "/expenses").getBody()), "expenses"))
                .as("nothing was written")
                .isEmpty();
    }

    @Test
    void aRateSomebodyTypedIsUsedAndNothingIsLookedUp() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        // What the card was actually charged at, which no reference series knows.
        var created = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":8000,"currency":"JPY","fxRate":"0.0055",
                 "spentOn":"2027-05-01","paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        Map<String, Object> expense = asMap(created.getBody());
        assertThat(asLong(expense.get("amountMinor"))).isEqualTo(4400L);
        assertThat(expense).containsEntry("fxManual", true);
        assertThat(expense)
                .as("a typed rate has no publication date, and inventing one would dress it up as a quote")
                .containsEntry("fxQuotedOn", null);
        verify(fxRateClient, never()).ratesPerEur(any(), anySet());
    }

    @Test
    void anExpenseInTheTripsOwnCurrencyTroublesNobody() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        var created = post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Coffee","amountMinor":350,"spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        Map<String, Object> expense = asMap(created.getBody());
        assertThat(asLong(expense.get("amountMinor"))).isEqualTo(350L);
        // The absence of all of it is what "paid in the trip's currency" means,
        // in the request, in the response and in the table.
        assertThat(expense).containsEntry("sourceCurrency", null);
        assertThat(expense).containsEntry("fxRate", null);
        // The commonest case by a distance, and it must stay free of the upstream.
        verify(fxRateClient, never()).ratesPerEur(any(), anySet());
    }

    @Test
    void editingBackIntoTheTripsCurrencyClearsTheConversion() {
        Session alice = register("alice");
        Object tripId = tripFor(alice, "EUR");
        Object aliceId = userIdOf(alice, tripId, alice);

        Object expenseId = asMap(post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":8000,"currency":"JPY","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId)).getBody()).get("id");

        // Entered in yen by mistake; corrected to euros.
        Map<String, Object> expense = asMap(put(alice, "/api/trips/" + tripId + "/expenses/" + expenseId, """
                {"description":"Dinner","amountMinor":4500,"currency":"EUR","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(aliceId, aliceId)).getBody());

        assertThat(asLong(expense.get("amountMinor"))).isEqualTo(4500L);
        assertThat(expense)
                .as("or the row goes on claiming a conversion that no longer happened")
                .containsEntry("sourceCurrency", null)
                .containsEntry("fxRate", null)
                .containsEntry("fxManual", false);
    }

    @Test
    void settlingUpInCashAbroadClearsTheBalanceItWasMeantTo() {
        Session alice = register("alice");
        Session bob = register("bob");
        Object tripId = tripFor(alice, "EUR");
        Object bobId = addMember(alice, tripId, bob);
        Object aliceId = userIdOf(alice, tripId, alice);

        // Alice fronts ¥8,000 — €44.67 — split evenly, so Bob owes her €22.33.
        post(alice, "/api/trips/" + tripId + "/expenses", """
                {"description":"Dinner","amountMinor":8000,"currency":"JPY","spentOn":"2027-05-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s},{"userId":%s}]}
                """.formatted(aliceId, aliceId, bobId));

        // Bob hands over ¥4,000 in cash, because cash is what he has.
        assertThat(post(bob, "/api/trips/" + tripId + "/expenses/payments", """
                {"fromUserId":%s,"toUserId":%s,"amountMinor":4000,"currency":"JPY","paidOn":"2027-05-01"}
                """.formatted(bobId, aliceId)).getStatusCode().value()).isEqualTo(201);

        Map<String, Object> ledger = asMap(get(alice, "/api/trips/" + tripId + "/expenses").getBody());
        List<Map<String, Object>> balances = listOf(mapOf(ledger, "summary"), "balances");

        // ¥8,000 is 4467, split 2234/2233 to the lowest user id, and ¥4,000 is
        // the 2233 Bob owes. Everybody is square — which only works because the
        // payment converted through the same path the expense did.
        assertThat(balances).allSatisfy(balance ->
                assertThat(asLong(balance.get("netMinor"))).isZero());
    }
}
