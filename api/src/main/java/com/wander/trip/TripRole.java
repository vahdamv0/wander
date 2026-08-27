package com.wander.trip;

/** Per-trip rights. Ordered least-privileged last is not implied — check explicitly. */
public enum TripRole {
    /** Can delete the trip and manage members. Exactly one per trip. */
    OWNER,
    /** Can change trip content. */
    EDITOR,
    /** Read-only. */
    VIEWER
}
