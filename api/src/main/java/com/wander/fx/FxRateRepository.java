package com.wander.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FxRateRepository extends JpaRepository<FxRate, Long> {

    /**
     * The cached rates for one day, for the currencies an expense needs — at
     * most two, so this is one indexed read on {@code uq_fx_rates} rather than a
     * lookup per currency.
     */
    List<FxRate> findByRateDateAndCurrencyIn(LocalDate rateDate, Collection<String> currencies);

    /**
     * Caches a rate, and does nothing if another request got there first.
     *
     * An upsert rather than a {@code save}, because this row is written on the
     * way through somebody's expense and two people entering foreign expenses at
     * once would otherwise collide on {@code uq_fx_rates} — where the loser does
     * not lose a cache entry, they lose their whole write, since the constraint
     * violation would roll the surrounding transaction back. Losing a coffee to a
     * duplicate rate is an absurd way to fail.
     *
     * Native because {@code ON CONFLICT} is Postgres's, which this project has
     * committed to everywhere else the migrations are: the tests run on real
     * Postgres for exactly this reason.
     */
    @Modifying
    @Query(value = """
            insert into fx_rates (rate_date, currency, rate_per_eur, quoted_on)
            values (:rateDate, :currency, :ratePerEur, :quotedOn)
            on conflict (rate_date, currency) do nothing
            """, nativeQuery = true)
    void cache(LocalDate rateDate, String currency, BigDecimal ratePerEur, LocalDate quotedOn);
}
