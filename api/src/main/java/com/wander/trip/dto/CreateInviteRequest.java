package com.wander.trip.dto;

import com.wander.trip.TripRole;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Mint a link.
 *
 * `role` may not be OWNER, for the same reason {@link AddMemberRequest}'s may
 * not: a trip has exactly one owner and a transfer is the only way it moves.
 *
 * `expiresInDays` is bounded rather than free. The upper bound is not
 * bureaucracy — a link is a credential that its holder can forward, and one good
 * for a year outlives everybody's memory of having sent it.
 */
public record CreateInviteRequest(
        @NotNull TripRole role,
        @Min(1) @Max(90) Integer expiresInDays) {

    /** A week: long enough to be answered after a weekend, short enough to be forgotten safely. */
    public int expiresInDaysOrDefault() {
        return expiresInDays == null ? 7 : expiresInDays;
    }
}
