package com.wander.user;

/**
 * An account has been taken out of service.
 *
 * A Spring application event rather than a direct call, for the reason
 * {@code TripChanges} publishes one: the service that disables an account has no
 * business knowing that WebSockets exist, and a service that had to would be
 * untestable without a transport. What listens is
 * {@code TripSyncBroadcaster}, which hangs up every socket the account holds.
 *
 * It is published inside the transaction and delivered after commit, so a
 * rollback cannot leave somebody disconnected from an account that was never
 * disabled.
 */
public record AccountDisabled(Long userId) {
}
