package com.wander.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * The conversion arithmetic, on its own — the companion to
 * {@code ExpenseSplitterTest} and tested for the same reason. A wrong answer
 * here is silent, permanent, and expressed in somebody's money.
 */
class CurrencyConversionTest {

    /** Roughly the real rate, and enough figures to show the rounding. */
    private static final BigDecimal YEN_TO_EUR = new BigDecimal("0.005583");

    @Test
    void theExponentsAreTheJdksAndNotOurs() {
        assertThat(CurrencyConversion.exponentOf("EUR")).isEqualTo(2);
        // The one that turns a mistake into a factor of a hundred.
        assertThat(CurrencyConversion.exponentOf("JPY")).isZero();
        assertThat(CurrencyConversion.exponentOf("KWD")).isEqualTo(3);
    }

    @Test
    void convertingAcrossDifferentExponentsShiftsThePoint() {
        // ¥8,000 is eight thousand whole yen, because the yen has no minor unit.
        // At 0.005583 that is €44.66 — 4466 cents, not 4466 of anything else.
        // Getting the shift wrong here is a factor of a hundred, which is the
        // only mistake in this file with the decency to be obvious.
        assertThat(CurrencyConversion.convert(8000, "JPY", "EUR", YEN_TO_EUR)).isEqualTo(4466);
    }

    @Test
    void convertingWithinOneExponentIsJustTheRate() {
        // 100.00 USD at 0.92 is 92.00 EUR: same number of decimal places on both
        // sides, so nothing moves but the rate.
        assertThat(CurrencyConversion.convert(10000, "USD", "EUR", new BigDecimal("0.92")))
                .isEqualTo(9200);
    }

    @Test
    void theOtherDirectionShiftsTheOtherWay() {
        // €44.66 back into yen at 179.11. The point moves left this time, and a
        // sign error would be visible in orbit.
        assertThat(CurrencyConversion.convert(4466, "EUR", "JPY", new BigDecimal("179.11")))
                .isEqualTo(7999);
    }

    @Test
    void aThreeDecimalCurrencyIsNotAssumedToHaveTwo() {
        // The dinar has three, and a hardcoded two would be out by a factor of
        // ten every time somebody went to Kuwait.
        assertThat(CurrencyConversion.convert(10000, "EUR", "KWD", new BigDecimal("0.35766")))
                .isEqualTo(35766);
    }

    @Test
    void roundingIsHalfUpAndHappensExactlyOnce() {
        // 1.005 of a euro-cent unit: the half goes up rather than to even, which
        // is what somebody checking a receipt by hand expects.
        assertThat(CurrencyConversion.convert(1000, "EUR", "EUR", new BigDecimal("1.0005")))
                .isEqualTo(1001);
    }

    @Test
    void aCrossRateComesFromTheTwoEuroLegs() {
        // Yen and dong, both quoted against the euro because that is the
        // direction with the significant figures. 179.11 / 30220 is what a yen
        // is worth in dong, and neither number was ever asked for as a pair.
        BigDecimal rate = CurrencyConversion.crossRate(new BigDecimal("179.11"), new BigDecimal("30220"));

        assertThat(rate.scale()).isEqualTo(CurrencyConversion.RATE_SCALE);
        assertThat(rate).isEqualByComparingTo(new BigDecimal("168.723131036793032"));
    }

    @Test
    void theEuroLegOfAEuroTripIsExactlyOne() {
        // Not fetched, and this is why: an instance whose trips are in euros must
        // not depend on a third party to confirm that one euro is one euro.
        assertThat(CurrencyConversion.crossRate(new BigDecimal("179.11"), BigDecimal.ONE))
                .isEqualByComparingTo(new BigDecimal("0.005583161185863"));
    }

    @Test
    void aRateThatWouldProduceNothingIsStillArithmetic() {
        // A hundred dong. It converts to zero cents, and the *service* refuses
        // that — this class's job is to say what the number is, not whether it
        // is a sensible expense.
        assertThat(CurrencyConversion.convert(100, "VND", "EUR", new BigDecimal("0.0000331")))
                .isZero();
    }

    @Test
    void anUnknownCodeIsRefusedRatherThanGuessedAt() {
        assertThatThrownBy(() -> CurrencyConversion.normalise("QQQ"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aThingThatIsNotACurrencyIsRefusedToo() {
        // Gold has an ISO code and no minor unit, so there is no amount of it
        // this feature can represent. The JDK answers -1; a class that trusted
        // that number would shift the point by minus one.
        assertThatThrownBy(() -> CurrencyConversion.exponentOf("XAU"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRealCurrencyHasAnExponentThisCanShiftBy() {
        // The bound exists because the exponent becomes a decimal shift, and a
        // wild one would expand rather than wrap — `setScale` would materialise
        // an unscaled integer with that many digits in it. Four is the real
        // maximum in ISO 4217, so nothing the JDK ships should be refused.
        assertThat(java.util.Currency.getAvailableCurrencies())
                .filteredOn(currency -> currency.getDefaultFractionDigits() >= 0)
                .allSatisfy(currency -> assertThat(
                        CurrencyConversion.exponentOf(currency.getCurrencyCode()))
                        .isBetween(0, 4));
    }

    @Test
    void aCodeIsNormalisedRatherThanRejectedForItsCase() {
        assertThat(CurrencyConversion.normalise(" jpy ")).isEqualTo("JPY");
    }

    @Test
    void aNonPositiveRateIsRefused() {
        assertThatThrownBy(() -> CurrencyConversion.convert(1000, "JPY", "EUR", BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
