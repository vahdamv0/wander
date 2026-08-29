package com.wander.trip.dto;

import com.wander.trip.TripRole;

import jakarta.validation.constraints.NotNull;

/**
 * What somebody holding a link is told before they accept it.
 *
 * Deliberately thin: the trip's name, who is inviting them, and what they would
 * be able to do. Not the itinerary — they are not a member yet, and an unusable
 * link must not become a way to read a trip.
 *
 * This endpoint is **authenticated**, like everything else here. Signing in first
 * and then seeing the invitation costs one extra step and keeps
 * `/api/config/sign-in` the single anonymous endpoint in this application.
 */
public record InvitePreview(
        @NotNull String tripName,
        @NotNull String invitedByName,
        @NotNull TripRole role,
        /** False for a link that is expired, revoked, already used — or your own trip. */
        @NotNull Boolean joinable,
        @NotNull String reason,
        /** Set when the caller is already on this trip, so the client can just send them there. */
        Long tripId) {
}
