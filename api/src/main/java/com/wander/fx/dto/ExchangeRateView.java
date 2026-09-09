package com.wander.fx.dto;

import java.time.LocalDate;

import com.wander.fx.FxService;

import jakarta.validation.constraints.NotNull;

/**
 * A rate, for the expense form to show before anything is saved.
 *
 * **Sent as a string, not a number**, and that is the same rule the rest of this
 * feature follows rather than an exception to it: money never crosses this wire
 * as a decimal, because JSON's number is a binary float on the other side and
 * this project has spent a lot of effort keeping one out of the ledger. A rate
 * has more significant figures than a float shows honestly, the client does no
 * arithmetic with it, and a string is what it needs to display it exactly.
 */
public record ExchangeRateView(
        @NotNull String from,
        @NotNull String to,
        /** The date the rate was asked for — the day the money was spent. */
        @NotNull LocalDate on,
        /** How many of {@code to} one of {@code from} buys. */
        @NotNull String rate,
        /**
         * The day the rate was actually published for, which is not always the
         * day asked for: rates exist on the days somebody publishes them, so a
         * Sunday's expense is honestly converted at Friday's rate rather than
         * being told a Sunday rate exists.
         */
        @NotNull LocalDate quotedOn,
        /**
         * What the caller's amount comes to, when one was given.
         *
         * Here, rather than left to the client to work out, because **the client
         * does no money arithmetic** — the rule the whole feature is built on.
         * A preview computed in TypeScript would be a second implementation of
         * the conversion, and the way it would fail is by disagreeing with the
         * saved figure by a cent: visible, wrong, and precisely the bug the
         * server-side-arithmetic rule exists to prevent. So the preview comes
         * from the same code path that will do the real conversion on save.
         *
         * Null when no amount was asked about, which is the form's state before
         * anybody has typed one.
         */
        Long convertedMinor) {

    public static ExchangeRateView of(FxService.Quote quote, Long convertedMinor) {
        return new ExchangeRateView(quote.from(), quote.to(), quote.on(),
                quote.rate().toPlainString(), quote.quotedOn(), convertedMinor);
    }
}
