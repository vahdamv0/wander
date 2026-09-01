package com.wander.demo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.wander.config.WanderProperties;
import com.wander.sync.TripChanges;
import com.wander.trip.Trip;
import com.wander.trip.TripRepository;
import com.wander.trip.TripRole;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * Clears the trips the published demo account made for itself.
 *
 * The demo account is shared: its password is printed on the sign-in page, so
 * every visitor to a demo instance is signed in as the same person and looking
 * at the same trip list. It is a *viewer* on the seeded trip, which is the whole
 * design — but it is an ordinary account otherwise, and nothing stops it
 * creating trips of its own, where it is the owner and may write whatever it
 * likes. Those trips are then on every later visitor's list, under whatever name
 * the last stranger typed.
 *
 * {@code DemoSeeder} does not deal with this and deliberately cannot: its delete
 * is scoped to the demo *content* owner's trips, which is what makes switching
 * the flag on by mistake cost nothing. Trips owned by the published account are
 * outside that scope, so they survive a restart, a rebuild and a redeploy alike
 * — the graffiti is rows, and only deleting rows removes it.
 *
 * **This is the first thing in wander that runs on a clock**, which is a rule the
 * rest of the project keeps: an invitation's status is derived from its
 * timestamps rather than written by a job, precisely so that nothing has to run
 * at the right moment for the data to be right. The exception is argued for
 * rather than assumed. The alternative was restarting the container on a cron to
 * make {@code DemoSeeder} run again — which drops every WebSocket, gives every
 * reader an outage, and re-dates the trip under whoever is looking at it, all to
 * achieve one {@code DELETE}. Nothing here is *derived*: a junk trip cannot be
 * hidden by a query somebody remembers to write, because the trip list is the
 * ordinary one every account uses.
 *
 * It also runs once at startup — a scheduled task's first pass is immediate — so
 * a restart still tidies up, and the sweep needs no separate hook in the seeder.
 * The two are ordered by nothing and need no ordering: they delete disjoint sets,
 * and a sweep that arrives before the account exists finds nothing and says so.
 *
 * Announced through {@code TripChanges} like any other deletion, so a visitor
 * looking at a trip that is swept out from under them is navigated away instead
 * of being left on a page whose every request now 404s. The actor is the demo
 * account itself, which is honest — these are its trips — and costs nothing: the
 * client only ignores its own echo while it is *saving*.
 */
public class DemoSweeper {

    private static final Logger log = LoggerFactory.getLogger(DemoSweeper.class);

    private final WanderProperties properties;
    private final UserRepository users;
    private final TripRepository trips;
    private final TripChanges changes;

    public DemoSweeper(WanderProperties properties, UserRepository users, TripRepository trips,
            TripChanges changes) {
        this.properties = properties;
        this.users = users;
        this.trips = trips;
        this.changes = changes;
    }

    /**
     * Deletes every trip the published account owns, and returns how many.
     *
     * `fixedDelay`, not `fixedRate`: a sweep that somehow took longer than the
     * interval should not have a second one starting behind it. Public and
     * returning a count so a test can call it directly — waiting out the
     * interval is not a test.
     */
    @Scheduled(fixedDelayString = "${wander.demo.sweep-minutes}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public int sweep() {
        Optional<User> visitor = users.findByEmailIgnoreCase(properties.demo().email());
        if (visitor.isEmpty()) {
            // First boot: the seeder makes the account, and may not have got
            // there yet. There is nothing to sweep either way.
            return 0;
        }

        List<Trip> owned = trips.findAllOwnedBy(visitor.get().getId(), TripRole.OWNER);
        if (owned.isEmpty()) {
            return 0;
        }

        // Ids first: they are needed after the rows are gone, and a deleted
        // entity is not a thing to read from.
        List<Long> ids = owned.stream().map(Trip::getId).toList();
        // Memberships, places, expenses and the rest go with each trip — the
        // schema declares ON DELETE CASCADE, as TripService.delete relies on.
        trips.deleteAll(owned);
        Long actor = visitor.get().getId();
        // AFTER_COMMIT, so nothing is announced that then rolls back.
        ids.forEach(tripId -> changes.tripDeleted(tripId, actor));

        log.info("Demo sweep removed {} trip(s) created by {}", ids.size(), properties.demo().email());
        return ids.size();
    }
}
