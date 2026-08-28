package com.wander.sync;

/**
 * What changed on a trip, at the coarsest granularity that still tells a client
 * which of its two reads to repeat.
 *
 * Deliberately not one value per operation: the client's answer to all of them
 * is "re-read", so a finer vocabulary would be detail nobody acts on, and every
 * new endpoint would have to invent a name for itself.
 */
public enum TripChangeKind {
    /** Places or day notes moved. Re-read the itinerary. */
    ITINERARY,
    /** The member list or somebody's role changed. Re-read members, and the trip. */
    MEMBERS,
    /** An expense, or its split, changed. Re-read the ledger. */
    EXPENSES,
    /** Something on the packing list changed. Re-read it. */
    PACKING,
    /** The trip is gone. Nothing to re-read. */
    TRIP_DELETED
}
