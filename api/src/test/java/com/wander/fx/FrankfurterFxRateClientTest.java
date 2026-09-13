package com.wander.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.wander.config.WanderProperties;
import com.wander.geo.RateGate;

import tools.jackson.databind.json.JsonMapper;

/**
 * The parsing, without the network — where the risk in that class actually is.
 *
 * The payloads here are shaped like the real ones, including the two that look
 * like nothing and are the whole reason this file exists: a date with no data
 * answers **200 with an empty array**, and a weak currency arrives in
 * **scientific notation**. Neither fails; both would produce a wrong number
 * quietly.
 */
class FrankfurterFxRateClientTest {

    private final FrankfurterFxRateClient client = new FrankfurterFxRateClient(
            properties(), JsonMapper.builder().build(), new RateGate(0, 1000));

    private static WanderProperties properties() {
        // Only the base URL is read in the constructor, and nothing in these
        // tests makes a request.
        return new WanderProperties("test", "", "", false, "EUR", null, null, null, null, null,
                null, null, null, null, null, new WanderProperties.Fx(true, "http://localhost",
                        24, "", "", 0, 1000),
                null, null);
    }

    @Test
    void ratesComeBackAsAnArrayOfPairs() {
        // The v2 shape. The older one was an object of rates keyed by code, which
        // is what most examples still show and what a parser written from memory
        // would expect — it would find nothing here and report an empty answer.
        FxRateClient.EurRates rates = client.parseRates("""
                [{"date":"2026-09-05","base":"EUR","quote":"JPY","rate":181.88},
                 {"date":"2026-09-05","base":"EUR","quote":"VND","rate":30220}]
                """);

        assertThat(rates.perEur()).containsEntry("JPY", new BigDecimal("181.88"));
        assertThat(rates.perEur()).containsEntry("VND", new BigDecimal("30220"));
        assertThat(rates.quotedOn()).isEqualTo(LocalDate.of(2026, 9, 5));
    }

    @Test
    void aRateInScientificNotationKeepsEveryFigureItArrivedWith() {
        // What the dong looks like when anything asks for it against the euro
        // rather than the other way round. Through a double this becomes an
        // approximation; the exponent form also parses as zero in any reader
        // that expects plain digits, which would be a free hotel.
        FxRateClient.EurRates rates = client.parseRates("""
                [{"date":"2026-09-09","base":"EUR","quote":"VND","rate":3.3e-05}]
                """);

        assertThat(rates.perEur().get("VND")).isEqualByComparingTo(new BigDecimal("0.000033"));
    }

    @Test
    void aDateWithNothingPublishedIsAnEmptyAnswerRatherThanAnError() {
        // A future date — somebody recording a deposit for a trip they have not
        // taken. The upstream answers 200 with an empty array, so this has to be
        // recognised here or it turns into a conversion at a rate of nothing.
        assertThat(client.parseRates("[]").isEmpty()).isTrue();
    }

    @Test
    void aCurrencyTheUpstreamCannotAnswerForIsSimplyAbsent() {
        // Two asked for, one returned. The missing one must not appear as zero:
        // the caller has to be able to tell "no rate" from "a rate of nought",
        // because only the first of those has an honest answer.
        FxRateClient.EurRates rates = client.parseRates("""
                [{"date":"2026-09-09","base":"EUR","quote":"JPY","rate":179.11}]
                """);

        assertThat(rates.perEur()).containsOnlyKeys("JPY");
    }

    @Test
    void aNonPositiveRateIsDroppedRatherThanStored() {
        // Nothing should ever send this. If something does, the alternative to
        // dropping it is a division by zero in the middle of the money
        // arithmetic.
        FxRateClient.EurRates rates = client.parseRates("""
                [{"date":"2026-09-09","base":"EUR","quote":"JPY","rate":0}]
                """);

        assertThat(rates.isEmpty()).isTrue();
    }

    @Test
    void nonsenseIsAnEmptyAnswerAndNotAnException() {
        assertThat(client.parseRates("<html>502 Bad Gateway</html>").isEmpty()).isTrue();
        assertThat(client.parseRates("").isEmpty()).isTrue();
        assertThat(client.parseRates(null).isEmpty()).isTrue();
    }

    @Test
    void theCurrencyListingIsReadForItsCodesAndNothingElse() {
        assertThat(client.parseCurrencies("""
                [{"iso_code":"AED","name":"United Arab Emirates Dirham","start_date":"1996-04-11"},
                 {"iso_code":"JPY","name":"Japanese Yen","start_date":"1999-01-04"}]
                """)).containsExactlyInAnyOrder("AED", "JPY");
    }

    @Test
    void aCurrencyListingThatIsNotOneComesBackEmpty() {
        assertThat(client.parseCurrencies("{\"status\":404}")).isEmpty();
    }
}
