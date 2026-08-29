package com.wander.trip.dto;

import java.time.Instant;

import com.wander.trip.InviteStatus;
import com.wander.trip.TripInvite;
import com.wander.trip.TripRole;

import jakarta.validation.constraints.NotNull;

/**
 * One invitation, as its trip's owner sees it.
 *
 * **No token.** The server keeps only a hash, so it could not put one here even
 * if this record asked for it — the link is returned once, by
 * {@link CreatedInviteView}, and never again. That is the same bargain as the
 * generated admin password printed once to the container log: a credential you
 * can re-read is a credential stored in the clear.
 */
public record TripInviteView(
        @NotNull Long id,
        @NotNull TripRole role,
        @NotNull InviteStatus status,
        @NotNull String createdByName,
        @NotNull Instant createdAt,
        @NotNull Instant expiresAt,
        String acceptedByName,
        Instant acceptedAt) {

    public static TripInviteView of(TripInvite invite, Instant now) {
        return new TripInviteView(
                invite.getId(),
                invite.getRole(),
                invite.statusAt(now),
                invite.getCreatedBy().getDisplayName(),
                invite.getCreatedAt(),
                invite.getExpiresAt(),
                invite.getAcceptedBy() == null ? null : invite.getAcceptedBy().getDisplayName(),
                invite.getAcceptedAt());
    }
}
