package com.wander.route;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.FeatureDisabledException;
import com.wander.config.WanderProperties;
import com.wander.place.Place;
import com.wander.place.PlaceRepository;
import com.wander.route.dto.DayRoutePreview;
import com.wander.route.dto.RouteStopView;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripRole;

/**
 * Sorting a day by how long it takes to get between its stops.
 *
 * <h2>This reads. It does not write.</h2>
 *
 * The method is {@code @Transactional(readOnly = true)} and there is no code
 * path out of this class that touches a row — applying a proposal is an
 * ordinary reorder through {@code PlaceService}. So there is one write path
 * with one set of rules, one {@code TripChanges} event, nothing to roll back if
 * the engine answers nonsense, and no second way for ranks to be assigned. That
 * is the same argument booking import makes about parsing a file, and
 * {@code previewWritesNothing} is the test that holds it here.
 *
 * <h2>Why an editor, for a read</h2>
 *
 * {@code requireRole(CAN_EDIT)} rather than {@code requireMember}: a proposal
 * is the first half of a write, and a VIEWER cannot perform the second. Letting
 * them ask would spend the instance's routing budget to produce something they
 * are not allowed to use — the demo account, on a public instance, is exactly
 * that shape.
 *
 * <h2>Off is a 503, not an empty answer</h2>
 *
 * The forecast swallows its outages and answers 200 with nothing, because
 * weather is decoration on a page whose job is the itinerary. That argument
 * does not survive here: a sort that quietly answers "no change" tells somebody
 * their day is already in the best order, which is a claim rather than an
 * absence. So an instance with routing off refuses, and the client knows not to
 * offer the button in the first place.
 */
@Service
public class RouteService {

    /** Who may sort a day. The same set that may reorder one, and for that reason. */
    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    private final RouteClient routes;
    private final PlaceRepository places;
    private final TripAccessService access;
    private final WanderProperties.Routing config;

    public RouteService(RouteClient routes, PlaceRepository places, TripAccessService access,
            WanderProperties properties) {
        this.routes = routes;
        this.places = places;
        this.access = access;
        this.config = properties.routing();
    }

    @Transactional(readOnly = true)
    public DayRoutePreview preview(Long userId, Long tripId, LocalDate dayDate,
            RouteProfile profile) {
        access.requireRole(tripId, userId, CAN_EDIT);
        if (!config.enabled()) {
            throw new FeatureDisabledException("Route sorting is switched off on this instance");
        }

        List<Place> day = places.findByTripIdAndDayDateOrderBySortOrderAsc(tripId, dayDate);
        if (day.size() > config.maxStops()) {
            throw new IllegalArgumentException("A day can be sorted with at most "
                    + config.maxStops() + " places on it; this one has " + day.size() + ".");
        }
        // Two located stops is the least that can be reordered at all. Refused
        // rather than answered with "no change", which would read as "your day
        // is already best" — see the class note.
        if (day.stream().filter(RouteService::isLocated).count() < 2) {
            throw new IllegalArgumentException(
                    "This day needs at least two places with a location before it can be sorted.");
        }

        // Every stop goes to the engine, including the pinned ones: their legs
        // are part of what the day costs, and leaving them out would compare a
        // proposal against a different day.
        List<Place> located = day.stream().filter(RouteService::isLocated).toList();
        RouteClient.Matrix matrix = routes.table(profile, located.stream()
                .map(place -> new RouteClient.Point(place.getLatitude(), place.getLongitude()))
                .toList());

        boolean[] pinned = new boolean[located.size()];
        long[] ids = new long[located.size()];
        for (int i = 0; i < located.size(); i++) {
            pinned[i] = located.get(i).isLocked();
            ids[i] = located.get(i).getId();
        }

        int[] order = DayRouteOptimiser.order(matrix.seconds(), pinned, ids);
        long current = DayRouteOptimiser.total(identity(located.size()), matrix.seconds());
        long proposed = DayRouteOptimiser.total(order, matrix.seconds());
        long metres = DayRouteOptimiser.total(order, matrix.metres());

        return new DayRoutePreview(dayDate, profile.name(), stops(day, located, order),
                current, proposed, metres, changed(order), config.attribution(),
                config.attributionUrl());
    }

    /**
     * The proposal, as the whole day rather than as the routed part of it.
     *
     * A place with no coordinates keeps the slot it is in — it was never sent
     * upstream, and moving a stop the engine could not see would be rearranging
     * somebody's plan around a guess. So the sorted stops are dealt back into
     * the slots those places are not occupying, which is exactly what the
     * optimiser does with a locked one, one level up.
     */
    private static List<RouteStopView> stops(List<Place> day, List<Place> located, int[] order) {
        List<RouteStopView> proposal = new ArrayList<>(day.size());
        int next = 0;
        for (Place place : day) {
            if (!isLocated(place)) {
                proposal.add(view(place, true));
                continue;
            }
            proposal.add(view(located.get(order[next++]), false));
        }
        return List.copyOf(proposal);
    }

    private static RouteStopView view(Place place, boolean unlocated) {
        boolean pinned = unlocated || place.isLocked();
        return new RouteStopView(place.getId(), place.getName(), pinned, place.isLocked(),
                !unlocated);
    }

    private static boolean changed(int[] order) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] != i) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocated(Place place) {
        return place.getLatitude() != null && place.getLongitude() != null;
    }

    private static int[] identity(int size) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        return order;
    }
}
