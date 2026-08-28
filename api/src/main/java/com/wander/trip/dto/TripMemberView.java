package com.wander.trip.dto;

import java.time.Instant;

import com.wander.trip.TripMember;
import com.wander.trip.TripRole;

import jakarta.validation.constraints.NotNull;

/**
 * One person on a trip.
 *
 * Keyed by `userId`, not by the membership row's own id: every operation the
 * client performs names the person ("remove Bob"), and the membership id is an
 * implementation detail of the join table.
 *
 * The email is here because it is how a member was added in the first place, so
 * it is what distinguishes two people with the same display name. It is only
 * ever shown to other members of the same trip.
 */
public record TripMemberView(
        // @NotNull is what makes springdoc mark these `required`; without it the
        // generated TypeScript field is optional and every use needs a `!`.
        @NotNull Long userId,
        @NotNull String displayName,
        @NotNull String email,
        @NotNull TripRole role,
        @NotNull Instant joinedAt) {

    public static TripMemberView of(TripMember member) {
        return new TripMemberView(member.getUser().getId(), member.getUser().getDisplayName(),
                member.getUser().getEmail(), member.getRole(), member.getJoinedAt());
    }
}
