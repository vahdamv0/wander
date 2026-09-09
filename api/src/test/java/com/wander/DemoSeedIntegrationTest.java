package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.wander.demo.DemoSweeper;

/**
 * The seeded demo trip, and the promise that the published account can only read
 * it.
 *
 * That promise is the whole feature. The credentials are printed on the sign-in
 * page of a public instance, so "viewer" has to be enforced by the server rather
 * than by which buttons the client draws — and nothing else in the suite would
 * notice if the seeder handed out `EDITOR` one day, because every write would
 * simply start succeeding.
 *
 * Its own context (the property override gives it one) because seeding runs at
 * boot and writes rows that the rest of the suite has no reason to see.
 */
@TestPropertySource(properties = {
        "wander.demo.enabled=true",
        "wander.demo.email=demo-seed-test@wander.local",
        "wander.demo.password=demo-traveller",
        "wander.demo.sweep-minutes=45" })
class DemoSeedIntegrationTest extends IntegrationTestBase {

    @Autowired
    private DemoSweeper sweeper;

    private Session demo() {
        return login("demo-seed-test@wander.local", "demo-traveller");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> theTrip(Session session) {
        List<Map<String, Object>> trips = json.readValue(
                get(session, "/api/trips").getBody(), List.class);
        assertThat(trips).hasSize(1);
        return trips.get(0);
    }

    @Test
    void thePublishedAccountCanSignInAndSeeOneSeededTrip() {
        Map<String, Object> trip = theTrip(demo());
        assertThat(trip).containsEntry("name", "Japan in Autumn");
        assertThat(trip).containsEntry("myRole", "VIEWER");
    }

    /**
     * The load-bearing one. A viewer gets 403 rather than 404, because being
     * refused a write is a different thing from not being on the trip at all.
     */
    @Test
    void thePublishedAccountCannotChangeAnything() {
        Session demo = demo();
        Object id = theTrip(demo).get("id");

        assertThat(post(demo, "/api/trips/" + id + "/places",
                "{\"dayDate\":\"2030-01-01\",\"name\":\"Somewhere\"}").getStatusCode().value())
                .isEqualTo(403);
        assertThat(put(demo, "/api/trips/" + id,
                "{\"name\":\"Renamed\",\"startDate\":\"2030-01-01\",\"endDate\":\"2030-01-05\"}")
                .getStatusCode().value()).isEqualTo(403);
        assertThat(delete(demo, "/api/trips/" + id).getStatusCode().value()).isEqualTo(403);
    }

    /**
     * The dates are computed from the day it was seeded, not written down. A
     * fixed range would drift out of the forecast horizon within a fortnight and
     * the day cards would quietly lose their weather — the failure this whole
     * re-seed-on-boot design exists to avoid.
     */
    @Test
    void theTripIsDatedFromWhenItWasSeeded() {
        Map<String, Object> trip = theTrip(demo());
        assertThat(java.time.LocalDate.parse((String) trip.get("startDate")))
                .isAfterOrEqualTo(java.time.LocalDate.now())
                .isBefore(java.time.LocalDate.now().plusDays(7));
    }

    /** The content that makes the demo worth looking at, rather than an empty trip. */
    @SuppressWarnings("unchecked")
    @Test
    void theTripCarriesTheThingsWorthDemonstrating() {
        Session demo = demo();
        Object id = theTrip(demo).get("id");

        Map<String, Object> itinerary = asMap(get(demo, "/api/trips/" + id + "/itinerary").getBody());
        List<Map<String, Object>> days = (List<Map<String, Object>>) itinerary.get("days");
        assertThat(days).hasSize(10);

        List<Map<String, Object>> allPlaces = days.stream()
                .flatMap(day -> ((List<Map<String, Object>>) day.get("places")).stream()).toList();
        assertThat(allPlaces).hasSize(10);
        // Seeded from real geocoder references, so the enrichment panel works on
        // a demo place exactly as it does on one somebody searched for.
        assertThat(allPlaces).allSatisfy(place -> assertThat(place).containsEntry("enrichable", true));
        // A photo is only stored with its credit, so any place that has one has both.
        assertThat(allPlaces).filteredOn(place -> place.get("photoUrl") != null)
                .isNotEmpty()
                .allSatisfy(place -> {
                    assertThat((String) place.get("photoAuthor")).isNotBlank();
                    assertThat((String) place.get("photoLicence")).isNotBlank();
                });
        // Some days have a note and some do not; an untouched day is the normal case.
        assertThat(days).filteredOn(day -> day.get("note") != null).isNotEmpty().hasSizeLessThan(10);

        assertThat((List<?>) asMap(get(demo, "/api/trips/" + id + "/reservations").getBody())
                .get("reservations")).hasSize(5);
        assertThat((List<?>) asMap(get(demo, "/api/trips/" + id + "/expenses").getBody())
                .get("expenses")).hasSize(8);
    }

    /**
     * One of the seeded expenses was paid in another currency, and the demo is
     * the only place that shape is on show without somebody typing it in.
     *
     * Asserted here for the reason the VIEWER role is: nothing else would
     * notice it breaking. A conversion that stopped happening would leave a
     * ledger that still adds up and still renders — it would just quietly be
     * counting pounds as yen, on the one instance strangers are looking at.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theDemoShowsAnExpensePaidInAnotherCurrency() {
        Session demo = demo();
        Object id = theTrip(demo).get("id");

        List<Map<String, Object>> expenses = (List<Map<String, Object>>) asMap(
                get(demo, "/api/trips/" + id + "/expenses").getBody()).get("expenses");
        List<Map<String, Object>> converted = expenses.stream()
                .filter(expense -> expense.get("sourceCurrency") != null)
                .toList();

        assertThat(converted).hasSize(1);
        Map<String, Object> flights = converted.get(0);
        assertThat(flights).containsEntry("sourceCurrency", "GBP");
        // Seeded with no network and no lookup, so it says a person entered the
        // rate rather than stamping a market quote onto a date that moves with
        // every re-seed.
        assertThat(flights).containsEntry("fxManual", true);
        assertThat(flights).containsEntry("fxQuotedOn", null);

        // The stored rate reproduces the stored amount: £1,028.00 at 208.63 is
        // ¥214,471.64, and the yen has no minor unit to keep the .64 in.
        long sourceMinor = ((Number) flights.get("sourceAmountMinor")).longValue();
        long amountMinor = ((Number) flights.get("amountMinor")).longValue();
        assertThat(sourceMinor).isEqualTo(102_800L);
        assertThat(new java.math.BigDecimal(sourceMinor)
                .multiply(new java.math.BigDecimal((String) flights.get("fxRate")))
                .movePointRight(0 - 2)
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact())
                .as("the rate on the row is the one that produced the amount on the row")
                .isEqualTo(amountMinor);

        // And the invariant everything else rests on survives the conversion.
        List<Map<String, Object>> shares = (List<Map<String, Object>>) flights.get("shares");
        assertThat(shares.stream().mapToLong(share -> ((Number) share.get("amountMinor")).longValue()).sum())
                .as("the split sums to the converted total, not to the pounds")
                .isEqualTo(amountMinor);
    }

    @Test
    void thePublishedAccountCannotLeaveTheTrip() {
        Session demo = demo();
        Object id = theTrip(demo).get("id");
        Object me = asMap(get(demo, "/api/auth/me").getBody()).get("id");

        assertThat(delete(demo, "/api/trips/" + id + "/members/" + me).getStatusCode().value())
                .isEqualTo(409);
        // Still there, and still a viewer: the refusal rolled nothing halfway.
        assertThat(theTrip(demo())).containsEntry("myRole", "VIEWER");
    }

    /**
     * The two fields the trips page needs to tell a visitor their work will be
     * deleted: the interval, which is a setting, and `demoAccount` below, which
     * is who they are. Neither is any use without the other, and a page that
     * said it to the wrong account or with the wrong number would be worse than
     * one that said nothing.
     */
    @Test
    void theSweepScheduleIsAnnouncedToTheClient() {
        assertThat(asMap(get(demo(), "/api/config").getBody()))
                .containsEntry("demoSweepMinutes", 45);
    }

    /** What the client reads to know it should not offer the control at all. */
    @Test
    void thePublishedAccountIsFlaggedAsTheDemoAccount() {
        assertThat(asMap(get(demo(), "/api/auth/me").getBody()))
                .containsEntry("demoAccount", true);
    }

    @Test
    void theSweepRemovesWhatAVisitorMadeAndLeavesTheSeededTrip() {
        Session demo = demo();
        assertThat(post(demo, "/api/trips",
                "{\"name\":\"Graffiti\",\"startDate\":\"2030-01-01\",\"endDate\":\"2030-01-03\"}")
                .getStatusCode().value()).isEqualTo(201);
        assertThat(asList(get(demo, "/api/trips").getBody())).hasSize(2);

        assertThat(sweeper.sweep()).isEqualTo(1);

        Map<String, Object> left = theTrip(demo);
        assertThat(left).containsEntry("name", "Japan in Autumn");
        assertThat(left).containsEntry("myRole", "VIEWER");
        assertThat(sweeper.sweep()).isZero();
    }
}
