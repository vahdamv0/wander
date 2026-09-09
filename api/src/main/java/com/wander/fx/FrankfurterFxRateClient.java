package com.wander.fx;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.wander.config.WanderProperties;
import com.wander.geo.RateGate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Frankfurter over HTTP.
 *
 * Chosen for the reason Open-Meteo was: **it needs no API key**. A self-hoster
 * should not have to register with a foreign-exchange vendor to record a dinner
 * in yen, and every keyed alternative makes the operator the customer of a
 * company this project has no business introducing them to. It is MIT-licensed
 * and self-hostable besides, which is why the base URL is a setting — an
 * instance that would rather run its own container changes one line and nothing
 * else moves.
 *
 * It aggregates rates published by central banks rather than being one bank's
 * table, which is worth knowing for two reasons. It is why the coverage extends
 * to the currencies a traveller actually needs — the dong, the baht, the rupiah
 * — rather than the couple of dozen majors a single reference series carries.
 * And it is why the label beside a converted amount says "market rate" with its
 * date rather than naming an institution: the number is a blend, and claiming a
 * provenance it does not have would be worse than describing it plainly.
 *
 * Nothing here throws for a date the upstream cannot answer. An empty result is
 * a real answer — see {@link FxRateClient#ratesPerEur} — and the decision about
 * what to do with one belongs above.
 */
@Component
public class FrankfurterFxRateClient implements FxRateClient {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterFxRateClient.class);

    private final RestClient http;
    private final ObjectMapper json;
    private final RateGate gate;

    public FrankfurterFxRateClient(WanderProperties properties, ObjectMapper json,
            RateGate frankfurterGate) {
        this.json = json;
        this.gate = frankfurterGate;
        this.http = RestClient.builder()
                .baseUrl(properties.fx().baseUrl())
                .defaultHeader("User-Agent",
                        "wander/" + properties.version() + " (self-hosted travel planner)")
                .defaultHeader("Accept", "application/json")
                .requestFactory(timeouts())
                .build();
    }

    /** Same shape and the same reasoning as {@code OpenMeteoWeatherClient.timeouts()}. */
    private static JdkClientHttpRequestFactory timeouts() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(6));
        return factory;
    }

    @Override
    public EurRates ratesPerEur(LocalDate date, Set<String> currencies) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String currency : currencies) {
            // The euro is the base of the question and never part of the answer;
            // asking for it back would work, and would also be the one currency
            // whose rate this class must never depend on an upstream for.
            if (!"EUR".equals(currency)) {
                wanted.add(currency);
            }
        }
        if (wanted.isEmpty()) {
            return EurRates.none();
        }

        gate.pass();
        String body = http.get()
                .uri(uri -> ratesUri(uri, date, wanted))
                .retrieve()
                .body(String.class);
        return parseRates(body);
    }

    @Override
    public Set<String> supportedCurrencies() {
        gate.pass();
        String body = http.get()
                .uri(uri -> uri.path("/v2/currencies").build())
                .retrieve()
                .body(String.class);
        return parseCurrencies(body);
    }

    /**
     * Always {@code base=EUR}, always an explicit {@code date}.
     *
     * Neither is incidental. The base is the precision argument on
     * {@link FxRateClient}. The explicit date is the one that bites without it:
     * asked for the latest rates, this upstream answers each currency with its
     * own most recent publication, so two quotes in one response can be stamped
     * with different days — and an expense that borrowed Tuesday's dollar and
     * Monday's pound would be converted through a cross rate that never existed.
     * An expense always has a date, so there is never a reason to ask without one.
     */
    private static URI ratesUri(UriBuilder uri, LocalDate date, Set<String> currencies) {
        return uri.path("/v2/rates")
                .queryParam("base", "EUR")
                .queryParam("date", date)
                .queryParam("quotes", String.join(",", currencies))
                .build();
    }

    /**
     * Package-private so the mapping can be tested without the network, which is
     * where the risk in this class actually lives.
     *
     * The payload is a **JSON array of pair records** — {@code {date, base,
     * quote, rate}} — not the object-of-rates shape the older version of this
     * API used and that most examples on the internet still show. A date with
     * nothing published comes back as an empty array with a 200, which is the
     * quiet failure this parser exists to turn into an honest empty answer.
     */
    EurRates parseRates(String body) {
        if (body == null || body.isBlank()) {
            return EurRates.none();
        }
        JsonNode records;
        try {
            records = json.readTree(body);
        } catch (RuntimeException ex) {
            log.warn("Exchange rate service answered with something that is not JSON: {}",
                    ex.getMessage());
            return EurRates.none();
        }
        if (!records.isArray()) {
            return EurRates.none();
        }

        LocalDate quotedOn = null;
        Map<String, BigDecimal> perEur = new LinkedHashMap<>();
        for (JsonNode record : records) {
            String quote = text(record, "quote");
            BigDecimal rate = decimal(record.get("rate"));
            if (quote == null || rate == null || rate.signum() <= 0) {
                // A non-positive rate is not a rate. Skipping it rather than
                // storing it is the difference between one missing conversion and
                // a division by zero inside the money arithmetic.
                continue;
            }
            perEur.put(quote.toUpperCase(java.util.Locale.ROOT), rate);
            if (quotedOn == null) {
                quotedOn = date(record.get("date"));
            }
        }
        return perEur.isEmpty() ? EurRates.none() : new EurRates(quotedOn, Map.copyOf(perEur));
    }

    /** The listing is an array of currency objects; only the code is wanted here. */
    Set<String> parseCurrencies(String body) {
        if (body == null || body.isBlank()) {
            return Set.of();
        }
        JsonNode records;
        try {
            records = json.readTree(body);
        } catch (RuntimeException ex) {
            log.warn("Exchange rate service answered with something that is not JSON: {}",
                    ex.getMessage());
            return Set.of();
        }
        if (!records.isArray()) {
            return Set.of();
        }

        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode record : records) {
            String code = text(record, "iso_code");
            if (code != null && code.length() == 3) {
                codes.add(code.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return Set.copyOf(codes);
    }

    private static String text(JsonNode record, String field) {
        JsonNode value = record.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /**
     * Read through the number's own text, never through a double.
     *
     * A weak currency against the euro arrives in scientific notation — the
     * dong quotes as {@code 3.3e-05} the moment anything asks for that direction
     * — and this is the money feature, where the standing rule is that no value
     * passes through a binary float on its way to being stored. {@code BigDecimal}
     * built from the printed form is exact and handles the exponent; the same
     * value via {@code doubleValue()} is an approximation nobody would notice
     * until a balance was a unit out.
     */
    private static BigDecimal decimal(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(value.asString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate date(JsonNode value) {
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return LocalDate.parse(value.asString());
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
