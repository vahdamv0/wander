package com.wander.fx;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.FeatureDisabledException;
import com.wander.common.UpstreamUnavailableException;
import com.wander.config.WanderProperties;

/**
 * Rates: the toggle, the cache, and what happens when the answer is no.
 *
 * Shaped like {@code GeocodingService} — the feature switch, the cache and the
 * shared gate live together, above a client that only knows how to make one
 * request. A new caller belongs behind this, not in a controller.
 *
 * **Where this deliberately differs from every other upstream in the project:
 * a failure here is an error.** Enrichment swallows a Wikipedia outage because a
 * description is a nicety; the forecast answers 200 with nothing because weather
 * is decoration on a page whose job is the itinerary. Neither argument survives
 * contact with money. There is no way to store an expense in a foreign currency
 * without a rate: a row with none either drops out of the totals or counts its
 * yen as euros, and both are wrong in a way that nothing later rechecks and
 * nobody notices until the balances are being argued over. So a missing rate
 * stops the write and says so, and the person is offered the one fallback that
 * always works — typing the rate their bank actually charged them.
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    private final FxRateClient client;
    private final FxRateRepository cache;
    private final WanderProperties.Fx config;

    /**
     * The supported-currency listing, held in memory rather than in a table.
     *
     * Unlike a rate, this is not a fact about a day — it is one list that moves
     * rarely, is wanted on every page that draws a currency picker, and is
     * worthless after a restart only until the first request. A table would be a
     * migration and a second thing to invalidate for something a field does
     * fine.
     */
    private volatile Set<String> supported = Set.of();
    private volatile Instant supportedAt = Instant.EPOCH;

    public FxService(FxRateClient client, FxRateRepository cache, WanderProperties properties) {
        this.client = client;
        this.cache = cache;
        this.config = properties.fx();
    }

    /** Whether this instance can look a rate up at all. */
    public boolean lookupEnabled() {
        return config.enabled();
    }

    /**
     * The currencies a rate can be found for.
     *
     * Empty is a meaningful answer and the client treats it as "offer the ISO
     * list and expect a typed rate": an instance with the lookup off, or one
     * whose first call has not landed, should still let somebody record a
     * dinner in yen.
     */
    public Set<String> supportedCurrencies() {
        if (!config.enabled()) {
            return Set.of();
        }
        Instant fresh = supportedAt.plus(Duration.ofHours(config.currencyCacheHours()));
        if (!supported.isEmpty() && Instant.now().isBefore(fresh)) {
            return supported;
        }
        try {
            Set<String> fetched = client.supportedCurrencies();
            if (!fetched.isEmpty()) {
                supported = fetched;
                supportedAt = Instant.now();
            }
        } catch (RuntimeException ex) {
            // The last known list outlives an outage: a stale currency list is
            // very nearly as good as a current one, and an empty picker is not.
            log.warn("Could not list supported currencies: {}", ex.getMessage());
        }
        return supported;
    }

    /**
     * The rate from one currency to another on a given day, looked up.
     *
     * Both legs come out of one call and one cache lookup — see
     * {@link FxRateClient} for why they must not be fetched as a pair or on
     * separate dates.
     */
    @Transactional
    public Quote quote(String from, String to, LocalDate on) {
        String source = CurrencyConversion.normalise(from);
        String target = CurrencyConversion.normalise(to);
        if (source.equals(target)) {
            return new Quote(source, target, on, BigDecimal.ONE, on);
        }
        if (!config.enabled()) {
            throw new FeatureDisabledException("Looking up exchange rates is switched off on this instance");
        }

        Map<String, Leg> legs = legsFor(on, source, target);
        Leg sourceLeg = legs.get(source);
        Leg targetLeg = legs.get(target);
        if (sourceLeg == null || targetLeg == null) {
            String missing = sourceLeg == null ? source : target;
            // 400, not 502: the upstream answered perfectly and the answer was
            // that it has nothing for that currency on that day — a date in the
            // future being much the commonest case, since somebody recording a
            // deposit is recording it before the rate exists. That is something
            // the person can fix, and the message has to say how.
            throw new IllegalArgumentException("No published exchange rate for " + missing + " on "
                    + on + ". Enter the rate you were charged instead.");
        }

        BigDecimal rate = CurrencyConversion.crossRate(sourceLeg.perEur(), targetLeg.perEur());
        // The later of the two, so the stamp never claims to be older than the
        // oldest thing it was computed from.
        LocalDate quotedOn = sourceLeg.quotedOn().isAfter(targetLeg.quotedOn())
                ? sourceLeg.quotedOn()
                : targetLeg.quotedOn();
        return new Quote(source, target, on, rate, quotedOn);
    }

    /**
     * Converts an amount, looking the rate up.
     *
     * @param on the date the money was spent — the rate is the rate of that day,
     *           not of today, because that is when the money left the account
     */
    @Transactional
    public Conversion convert(long sourceMinor, String from, String to, LocalDate on) {
        Quote quote = quote(from, to, on);
        return conversionOf(sourceMinor, quote.from(), quote.to(), quote.rate(), quote.quotedOn(), false);
    }

    /**
     * Converts at a rate somebody typed, with no lookup at all.
     *
     * The escape hatch, and it is more than that on two kinds of instance: one
     * with no outbound network has no other way to record a foreign expense, and
     * anybody reconciling against a card statement has a better rate than any
     * reference series — the one they were actually charged, spread included.
     */
    public Conversion convertAt(long sourceMinor, String from, String to, BigDecimal rate) {
        String source = CurrencyConversion.normalise(from);
        String target = CurrencyConversion.normalise(to);
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("An exchange rate has to be more than zero");
        }
        BigDecimal scaled = rate.setScale(CurrencyConversion.RATE_SCALE, java.math.RoundingMode.HALF_UP);
        if (scaled.signum() <= 0) {
            throw new IllegalArgumentException("That exchange rate is too small to use");
        }
        return conversionOf(sourceMinor, source, target, scaled, null, true);
    }

    /**
     * Converts at a rate that is already on the row, looking nothing up.
     *
     * This is what "frozen at entry" is made of. Editing an expense re-runs the
     * arithmetic — the amount may have changed, and the split with it — but it
     * must not re-run the *lookup*, because the money left the account at the
     * rate of the day and a later rate does not make that untrue. Without this
     * path, correcting a spelling mistake in a description would silently move
     * everybody's balance, which is exactly the kind of thing nobody notices
     * until the numbers are being argued over.
     */
    public Conversion reapply(long sourceMinor, String from, String to, BigDecimal rate,
            LocalDate quotedOn, boolean manual) {
        return conversionOf(sourceMinor, CurrencyConversion.normalise(from),
                CurrencyConversion.normalise(to), rate, quotedOn, manual);
    }

    private Conversion conversionOf(long sourceMinor, String from, String to, BigDecimal rate,
            LocalDate quotedOn, boolean manual) {
        long converted = CurrencyConversion.convert(sourceMinor, from, to, rate);
        if (converted < 1) {
            // A hundred dong is a fraction of a euro cent. Storing zero would
            // make an expense that exists and costs nothing, which reads as a
            // bug in the ledger rather than as a very small purchase.
            throw new IllegalArgumentException(
                    "That comes to less than the smallest unit of " + to);
        }
        return new Conversion(converted, from, sourceMinor, rate, quotedOn, manual);
    }

    /**
     * Both legs for a day, from the cache where possible and the upstream for
     * whatever is missing.
     *
     * The cache is checked before the toggle-guarded call and written after it,
     * and the write is an upsert rather than a save: two people entering yen
     * expenses at the same moment would otherwise race for one row and one of
     * them would lose their whole write to a duplicate key, which is a very
     * silly way to fail to record a coffee.
     */
    private Map<String, Leg> legsFor(LocalDate on, String source, String target) {
        Map<String, Leg> legs = new HashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String currency : Set.of(source, target)) {
            if ("EUR".equals(currency)) {
                // The base of every quote, and never fetched. An instance whose
                // trips are all in euros still converts yen with one leg.
                legs.put(currency, new Leg(BigDecimal.ONE, on));
            } else {
                missing.add(currency);
            }
        }
        if (missing.isEmpty()) {
            return legs;
        }

        for (FxRate cached : cache.findByRateDateAndCurrencyIn(on, missing)) {
            legs.put(cached.getCurrency(), new Leg(cached.getRatePerEur(), cached.getQuotedOn()));
            missing.remove(cached.getCurrency());
        }
        if (missing.isEmpty()) {
            return legs;
        }

        FxRateClient.EurRates fetched;
        try {
            fetched = client.ratesPerEur(on, missing);
        } catch (RuntimeException ex) {
            log.warn("Exchange rate lookup for {} failed: {}", on, ex.getMessage());
            throw new UpstreamUnavailableException(
                    "Could not reach the exchange rate service. Try again, or enter the rate yourself.");
        }
        if (fetched.isEmpty()) {
            return legs;
        }

        LocalDate quotedOn = fetched.quotedOn() == null ? on : fetched.quotedOn();
        fetched.perEur().forEach((currency, perEur) -> {
            if (missing.contains(currency)) {
                legs.put(currency, new Leg(perEur, quotedOn));
                cache.cache(on, currency, perEur, quotedOn);
            }
        });
        return legs;
    }

    /** One currency against the euro, with the day the upstream stamped it. */
    private record Leg(BigDecimal perEur, LocalDate quotedOn) {
    }

    /** A rate on its own, for the form's preview and for the client to show before saving. */
    public record Quote(String from, String to, LocalDate on, BigDecimal rate, LocalDate quotedOn) {
    }

    /**
     * A converted amount and everything needed to explain it afterwards.
     *
     * {@code quotedOn} is null for a rate somebody typed, because there is no
     * publication date to name and inventing one would dress a person's own
     * figure up as a market quote.
     */
    public record Conversion(long targetMinor, String sourceCurrency, long sourceMinor,
            BigDecimal rate, LocalDate quotedOn, boolean manual) {
    }
}
