package com.wander.sync;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns a committed change into frames on the wire.
 *
 * `AFTER_COMMIT` is the whole point of this class existing rather than the
 * services calling the handler directly: a plain listener would announce writes
 * that then rolled back, and every client would re-read to find nothing changed
 * — or worse, would have been told about a state the database never reached.
 *
 * `fallbackExecution` covers the events published with no transaction around
 * them; without it those vanish silently, which is a confusing thing to debug.
 */
@Component
public class TripSyncBroadcaster {

    private final TripSyncHandler handler;

    public TripSyncBroadcaster(TripSyncHandler handler) {
        this.handler = handler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTripChange(TripChange change) {
        handler.broadcast(change);
    }
}
