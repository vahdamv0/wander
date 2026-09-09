package com.wander.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One currency's rate against the euro on one day.
 *
 * The strongest cache in this project, and the only one with no TTL: a rate
 * published for a past date is not merely fresh, it is **final**. Enrichments
 * expire in weeks because a place gains a phone number; forecasts expire in
 * hours because a forecast is a guess that improves. What the euro bought on the
 * ninth of September stops being a moving target the moment the day ends, so a
 * row here is kept until the trip is.
 *
 * Global rather than per trip or per user, like {@code place_enrichment} and
 * unlike {@code day_weather}: two people converting yen on the same day are
 * asking one question, not two.
 */
@Entity
@Table(name = "fx_rates")
public class FxRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The day the rate is being used for — what was asked. The cache key. */
    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "rate_per_eur", nullable = false, precision = 30, scale = 15)
    private BigDecimal ratePerEur;

    /**
     * The day the upstream said the rate belonged to, which is not always the
     * day asked for — rates exist on the days somebody publishes them. Kept
     * apart from {@code rateDate} so that a Saturday's expense can honestly say
     * it was converted at Friday's rate rather than quietly claiming a Saturday
     * rate exists.
     */
    @Column(name = "quoted_on", nullable = false)
    private LocalDate quotedOn;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    protected FxRate() {
        // JPA
    }

    public FxRate(LocalDate rateDate, String currency, BigDecimal ratePerEur, LocalDate quotedOn) {
        this.rateDate = rateDate;
        this.currency = currency;
        this.ratePerEur = ratePerEur;
        this.quotedOn = quotedOn;
        this.fetchedAt = Instant.now();
    }

    public LocalDate getRateDate() {
        return rateDate;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getRatePerEur() {
        return ratePerEur;
    }

    public LocalDate getQuotedOn() {
        return quotedOn;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
