package com.wander.admin.dto;

import java.time.Instant;

import com.wander.admin.PasswordReset;
import com.wander.admin.ResetStatus;

import jakarta.validation.constraints.NotNull;

/**
 * One reset link, as the administrator who minted it sees it afterwards.
 *
 * **No token**, exactly as {@code TripInviteView} carries none: the server keeps
 * only a digest. The list answers "is that link I sent still live, and did they
 * ever use it" — which is the question a control whose purpose is taking access
 * away has to be able to answer.
 */
public record PasswordResetView(
        @NotNull Long id,
        @NotNull ResetStatus status,
        @NotNull String createdByName,
        @NotNull Instant createdAt,
        @NotNull Instant expiresAt,
        Instant usedAt) {

    public static PasswordResetView of(PasswordReset reset, Instant now) {
        return new PasswordResetView(reset.getId(), reset.statusAt(now), reset.getCreatedBy().getDisplayName(),
                reset.getCreatedAt(), reset.getExpiresAt(), reset.getUsedAt());
    }
}
