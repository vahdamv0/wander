package com.wander.sync;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * How a service announces a change.
 *
 * Services depend on this rather than on anything WebSocket-shaped: it publishes
 * a Spring application event and returns. What listens — a socket today, another
 * instance's message bus if this ever runs more than one — is not their
 * business, and a service that had to know would be untestable without a
 * transport.
 */
@Component
public class TripChanges {

    private final ApplicationEventPublisher events;

    public TripChanges(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void itineraryChanged(Long tripId, Long actorUserId) {
        events.publishEvent(TripChange.itinerary(tripId, actorUserId));
    }

    public void expensesChanged(Long tripId, Long actorUserId) {
        events.publishEvent(TripChange.expenses(tripId, actorUserId));
    }

    public void packingChanged(Long tripId, Long actorUserId) {
        events.publishEvent(TripChange.packing(tripId, actorUserId));
    }

    public void reservationsChanged(Long tripId, Long actorUserId) {
        events.publishEvent(TripChange.reservations(tripId, actorUserId));
    }

    public void membersChanged(Long tripId, Long actorUserId) {
        events.publishEvent(TripChange.members(tripId, actorUserId));
    }

    public void accessRevoked(Long tripId, Long actorUserId, Long revokedUserId) {
        events.publishEvent(TripChange.membersRevoking(tripId, actorUserId, revokedUserId));
    }

    public void tripDeleted(Long tripId, Long actorUserId) {
        events.publishEvent(TripChange.deleted(tripId, actorUserId));
    }
}
