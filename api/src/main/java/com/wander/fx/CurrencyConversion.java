package com.wander.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * Turning an amount in one currency into an amount in another, and nothing else.
 *
 * No database, no Spring, no network — the same bargain {@link
 * com.wander.expense.ExpenseSplitter} makes, and for the same reason: this is
 * the part of the feature where a wrong answer is silent, permanent and
 * expressed in somebody's money, so it is a pure function of its inputs and it
 * is tested exhaustively.
 *
 * **This is the one place in the money feature that is allowed a decimal type,
 * and the exception is narrow.** Everything on either side of it is an integer
 * count of minor units, which is what makes a balance addition that cannot
 * drift. But a rate is not a quantity of money and has no minor unit — 179.11
 * yen to the euro is not 17911 of anything — so it cannot be an integer, and the
 * multiplication has to happen somewhere. It happens here, in {@code
 * BigDecimal}, which is exact and rounds when told to rather than when the
 * hardware feels like it, and the result leaves as a {@code long} again. A
 * {@code double} would be the same code and would be wrong roughly never, which
 * is the worst failure rate a money bug can have.
 */
public final class CurrencyConversion {

    /**
     * The scale a cross rate is stored and applied at. Wide enough that the
     * weakest currency against the strongest still carries plenty of
     * significant figures — the dinar against the dong is around 0.0000118, and
     * fifteen places leaves eleven of them.
     */
    public static final int RATE_SCALE = 15;

    /** Plenty for a division of two published rates; the result is rounded to scale anyway. */
    private static final MathContext DIVISION = MathContext.DECIMAL128;

    /**
     * The most decimal places a currency is allowed to have here. Four is the
     * real maximum in ISO 4217 (the Chilean unit of account), so this is a
     * ceiling rather than a limit anybody meets.
     */
    private static final int MAX_EXPONENT = 4;

    private CurrencyConversion() {
    }

    /**
     * How many minor units of {@code to} the given minor units of {@code from}
     * come to, at {@code rate}.
     *
     * The two exponents are why this cannot be a plain multiplication. ¥3121 is
     * three thousand one hundred and twenty-one whole yen because the yen has no
     * minor unit, while 3121 euro-cents is €31.21 — so converting between them
     * has to shift the point by the difference as well as apply the rate.
     * Getting that wrong is a factor of a hundred, which at least has the virtue
     * of being obvious; every other mistake here is not.
     *
     * Rounded HALF_UP exactly once, at the end. Rounding the rate and then the
     * amount would round twice.
     */
    public static long convert(long sourceMinor, String from, String to, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("A conversion needs a positive rate");
        }
        int shift = Math.subtractExact(exponentOf(to), exponentOf(from));
        BigDecimal converted = BigDecimal.valueOf(sourceMinor)
                .multiply(rate)
                .movePointRight(shift)
                .setScale(0, RoundingMode.HALF_UP);
        try {
            return converted.longValueExact();
        } catch (ArithmeticException ex) {
            // Reachable only by an amount nobody could have spent, but the
            // alternative is a silently wrapped negative in the ledger.
            throw new IllegalArgumentException("That amount is too large to convert");
        }
    }

    /**
     * The rate from one currency to another, given what one euro buys of each.
     *
     * Both legs arrive as "per euro" because that is the direction an upstream
     * publishes with the most significant figures — see {@link FxRateClient}.
     * The euro is its own leg at exactly one, stated here rather than looked up:
     * a euro-denominated trip must not depend on a third party to confirm that
     * one euro is one euro.
     *
     * Rounded to {@link #RATE_SCALE} **before** it is used to convert anything,
     * which is deliberate. The rate is shown to people beside the amount it
     * produced, and a stored rate that does not reproduce the stored amount is
     * the kind of discrepancy somebody finds while reconciling a statement at
     * midnight and cannot explain.
     */
    public static BigDecimal crossRate(BigDecimal fromPerEur, BigDecimal toPerEur) {
        if (fromPerEur == null || toPerEur == null
                || fromPerEur.signum() <= 0 || toPerEur.signum() <= 0) {
            throw new IllegalArgumentException("A cross rate needs both legs");
        }
        return toPerEur.divide(fromPerEur, DIVISION).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Decimal places for a currency: 2 for the euro, 0 for the yen, 3 for the
     * dinar.
     *
     * From the JDK's own tables, for the reason {@code money.ts} takes it from
     * {@code Intl}: a table of exponents maintained in this repository would be
     * a table that is wrong somewhere, and being wrong about the yen is being
     * wrong by a factor of a hundred.
     */
    public static int exponentOf(String currency) {
        int digits = currencyOf(currency).getDefaultFractionDigits();
        if (digits < 0) {
            // -1 is the JDK's answer for the metals and the IMF's unit of
            // account. They have no minor unit, so nothing in this feature can
            // represent an amount of one.
            throw new IllegalArgumentException(currency + " is not a currency amounts can be held in");
        }
        if (digits > MAX_EXPONENT) {
            throw new IllegalArgumentException(currency + " has more decimal places than money can have");
        }
        return digits;
    }

    /**
     * Validates and normalises a code, or refuses it.
     *
     * The refusal matters more than it looks: the code arrives in a request
     * body, and an unknown one that reached the database would produce rows
     * whose amounts have no defined number of decimal places — unformattable,
     * unconvertible, and only discovered by whoever opens the page.
     */
    public static String normalise(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("A currency is required");
        }
        String code = currency.strip().toUpperCase(Locale.ROOT);
        exponentOf(code);
        return code;
    }

    private static Currency currencyOf(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException("'" + code + "' is not a currency code");
        }
    }
}
