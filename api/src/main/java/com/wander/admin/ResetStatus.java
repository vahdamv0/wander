package com.wander.admin;

/**
 * What became of a reset link, derived from its timestamps rather than stored.
 * See {@code InviteStatus}, which is the same shape for the same reason.
 */
public enum ResetStatus {
    PENDING,
    USED,
    REVOKED,
    EXPIRED
}
