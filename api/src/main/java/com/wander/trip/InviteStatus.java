package com.wander.trip;

/**
 * What became of an invitation, derived from its timestamps rather than stored.
 *
 * A stored status would need writing at the moment a link expires, and nothing in
 * this application runs on a clock.
 */
public enum InviteStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    EXPIRED
}
