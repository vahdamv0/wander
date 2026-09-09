package com.wander.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * The seam the tests replace, exactly as {@code GeocoderClient},
 * {@code EnrichmentClient}, {@code WeatherClient} and {@code MailClient} are for
 * their upstreams. Fifth of its kind, same bargain: one interface, one
 * implementation, and a suite that never touches the network.
 *
 * **Shaped as "these currencies against the euro on this date", not "this pair".**
 * That is the whole precision argument, and it belongs in the interface rather
 * than inside one implementation, because any replacement would have to make the
 * same choice. A rate is published to about five significant figures whichever
 * direction it is asked for, so the small side of a pair arrives pre-rounded:
 * asked for the dong against the euro an upstream answers 0.000033 — two
 * figures, worth over a percent of error on a hotel bill — where the euro
 * against the dong is 30127. Ask for the big numbers, divide here.
 *
 * The second reason is dates. Rates are published per currency by whoever
 * publishes them, so two currencies fetched separately can come back stamped
 * with different days; asked for together against one base on one date they
 * cannot.
 */
public interface FxRateClient {

    /**
     * How many of each currency one euro bought on {@code date}, or an empty
     * result when the upstream has nothing for that day.
     *
     * "Nothing" is a real answer and not an error: a date in the future has no
     * rate yet, which is exactly what happens when somebody records a deposit
     * for a trip they have not taken. The caller turns that into a refusal the
     * person can act on, because the alternative — storing an expense with no
     * rate — silently corrupts a balance that nothing will ever recheck.
     *
     * EUR itself is not expected in the answer and callers must not need it: one
     * euro is one euro, and relying on an upstream to say so is a way to fail on
     * a euro-denominated trip.
     */
    EurRates ratesPerEur(LocalDate date, Set<String> currencies);

    /**
     * The currencies this upstream can answer for at all.
     *
     * Asked so the client's picker only offers what will work. It is not the
     * ISO 4217 list and must not be assumed to be: coverage is the upstream's,
     * it moves, and a picker built from a compiled-in table would be wrong on
     * somebody's instance the way a compiled-in tile URL would be.
     */
    Set<String> supportedCurrencies();

    /**
     * What one euro bought, per currency, and the date the upstream stamped the
     * answer with — which may not be the date that was asked for. A missing
     * currency is simply absent from the map rather than present as zero.
     */
    record EurRates(LocalDate quotedOn, Map<String, BigDecimal> perEur) {

        public static EurRates none() {
            return new EurRates(null, Map.of());
        }

        public boolean isEmpty() {
            return perEur.isEmpty();
        }
    }
}
