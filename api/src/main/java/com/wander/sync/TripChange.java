package com.wander.sync;

import jakarta.validation.constraints.NotNull;

/**
 * One thing happened on one trip.
 *
 * **Invalidation, not state.** The payload says what changed, never how, and the
 * client answers by re-reading. That is not laziness: the server owns place
 * ranks and renumbers a whole day on every move, `PlaceRepo` already re-reads
 * after its own writes rather than patching, and a second serialisation path
 * would be a second thing that can disagree with `GET /itinerary`. The cost is
 * one small request per change per viewer, on a page that is one request to
 * begin with.
 *
 * `actorUserId` is who caused it, so the client that already re-read as part of
 * its own write can ignore the echo. `revokedUserId` is set only when somebody
 * lost access, and is what lets the handler hang up on them.
 */
public record TripChange(
        @NotNull Long tripId,
        @NotNull TripChangeKind kind,
        @NotNull Long actorUserId,
        Long revokedUserId) {

    public static TripChange itinerary(Long tripId, Long actorUserId) {
        return new TripChange(tripId, TripChangeKind.ITINERARY, actorUserId, null);
    }

    public static TripChange expenses(Long tripId, Long actorUserId) {
        return new TripChange(tripId, TripChangeKind.EXPENSES, actorUserId, null);
    }

    public static TripChange members(Long tripId, Long actorUserId) {
        return new TripChange(tripId, TripChangeKind.MEMBERS, actorUserId, null);
    }

    /** A member was removed, or demoted out of the trip's write side. */
    public static TripChange membersRevoking(Long tripId, Long actorUserId, Long revokedUserId) {
        return new TripChange(tripId, TripChangeKind.MEMBERS, actorUserId, revokedUserId);
    }

    public static TripChange deleted(Long tripId, Long actorUserId) {
        return new TripChange(tripId, TripChangeKind.TRIP_DELETED, actorUserId, null);
    }
}
