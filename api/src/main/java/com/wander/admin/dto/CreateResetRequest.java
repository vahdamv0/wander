package com.wander.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Mint a password reset link for one account.
 *
 * Bounded, and more tightly than an invitation: a link that sets a password is
 * handed over in a conversation that is happening now, so the useful lifetime is
 * measured in days and a fortnight is already generous.
 */
public record CreateResetRequest(@Min(1) @Max(14) Integer expiresInDays) {

    /**
     * Two days. Long enough for somebody in another timezone to read the message
     * and act on it, short enough that a link forgotten in a chat log is inert
     * by the weekend.
     */
    public int expiresInDaysOrDefault() {
        return expiresInDays == null ? 2 : expiresInDays;
    }
}
