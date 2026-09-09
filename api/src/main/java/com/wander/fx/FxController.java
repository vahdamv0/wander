package com.wander.fx;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wander.common.UpstreamQuota;
import com.wander.config.WanderProperties;
import com.wander.fx.dto.ExchangeRateView;
import com.wander.fx.dto.SupportedCurrencies;
import com.wander.security.WanderUser;

/**
 * Exchange rates, for the expense form.
 *
 * Two reads and no writes. Nothing here converts anything that gets stored —
 * {@code ExpenseService} does that on the way in, from the same service, because
 * a rate the client sent back would be a rate the client could choose. This is
 * the preview: what the conversion will be, shown before somebody commits to it,
 * so the number is not a surprise that appears in the ledger afterwards.
 *
 * Authenticated like everything else, and metered per caller. Both apply for the
 * geocoder's reasons — an open proxy hands a free service's budget to whoever
 * finds the address, and authentication alone stops nothing once anybody can get
 * an account.
 */
@RestController
@RequestMapping("/api/fx")
public class FxController {

    private final FxService fx;
    private final UpstreamQuota quota;
    private final WanderProperties.Fx config;

    public FxController(FxService fx, UpstreamQuota quota, WanderProperties properties) {
        this.fx = fx;
        this.quota = quota;
        this.config = properties.fx();
    }

    /**
     * What the picker may offer.
     *
     * Not metered, unlike the rate below: the answer is held in memory for a day
     * at a time for the whole instance, so a caller asking repeatedly is
     * spending this machine's cycles rather than somebody else's service — and
     * that is what the ordinary request handling is for.
     */
    @GetMapping("/currencies")
    public SupportedCurrencies listCurrencies() {
        List<String> codes = new ArrayList<>(fx.supportedCurrencies());
        codes.sort(String::compareTo);
        return new SupportedCurrencies(fx.lookupEnabled(), List.copyOf(codes),
                config.attribution(), config.attributionUrl());
    }

    /**
     * The rate between two currencies on a day.
     *
     * Named {@code getExchangeRate} rather than {@code get} because operation
     * ids are global on the generated client — a bare name here would collide
     * with another controller's.
     *
     * A day with no published rate is a 400 carrying the reason, not an empty
     * 200: the form has to be able to tell "there is no rate for a date in the
     * future" from "the rate is nothing", and only one of those has an answer
     * the person can act on.
     */
    @GetMapping("/rate")
    public ExchangeRateView getExchangeRate(@AuthenticationPrincipal WanderUser principal,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on,
            /*
             * Optional, and when it is given the answer carries what it converts
             * to. The form shows that number before anybody commits to it, and it
             * has to be this server's arithmetic rather than the browser's — see
             * ExchangeRateView.convertedMinor.
             */
            @RequestParam(required = false) Long amountMinor) {
        quota.rate(principal.id());
        FxService.Quote quote = fx.quote(from, to, on);
        Long converted = amountMinor == null || amountMinor < 1
                ? null
                : fx.reapply(amountMinor, quote.from(), quote.to(), quote.rate(),
                        quote.quotedOn(), false).targetMinor();
        return ExchangeRateView.of(quote, converted);
    }
}
