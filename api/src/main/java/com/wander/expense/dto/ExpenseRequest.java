package com.wander.expense.dto;

import java.time.LocalDate;
import java.util.List;

import com.wander.expense.SplitMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create or update — the same body, because an expense is written whole. A split
 * is not something you patch: changing who was in it changes everybody's share.
 *
 * The amount arrives already in minor units. The client does that conversion
 * because it is the side that knows the currency's exponent, and sending "12.34"
 * as a decimal would put a floating-point value on the wire in the one feature
 * that must not have one.
 *
 * **The amount and the shares are in {@code currency}, which is not necessarily
 * the trip's.** That is the whole shape of paying for something abroad: the bill
 * is in yen, so the total is typed in yen and an uneven split is argued over in
 * yen. The server converts once, on the way in, and stores the result in the
 * trip's currency — so nothing downstream of this record has to know that more
 * than one currency exists.
 */
public record ExpenseRequest(
        @NotBlank @Size(max = 160) String description,
        /** Minor units of {@code currency}, and more than nothing: a zero-cost expense is a note, not an expense. */
        @NotNull @Min(1) Long amountMinor,
        /**
         * What was actually paid, ISO 4217. Optional — absent means the trip's
         * own currency, which is the overwhelmingly common case and what every
         * client sent before this field existed.
         */
        @Size(min = 3, max = 3) String currency,
        /**
         * The rate to use, as a decimal string, when the caller would rather name
         * it than have it looked up.
         *
         * A **string**, like every other decimal this project puts on a wire: a
         * JSON number is a binary float on the far side, and this is the money
         * feature. Ignored when {@code currency} is the trip's — there is nothing
         * to convert — and otherwise it *replaces* the lookup rather than
         * overriding its result, so an instance with no outbound network can
         * still record a foreign expense and somebody with a card statement can
         * use the rate they were genuinely charged instead of a market reference.
         */
        String fxRate,
        @NotNull LocalDate spentOn,
        @NotNull Long paidByUserId,
        @NotNull SplitMode splitMode,
        /** Who shares it. For EXACT, each entry's amount must be present and they must sum to the total. */
        @NotEmpty List<@Valid ExpenseShareInput> shares) {
}
