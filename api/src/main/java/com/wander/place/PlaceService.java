package com.wander.place;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;
import com.wander.place.dto.CreatePlaceRequest;
import com.wander.place.dto.MovePlaceRequest;
import com.wander.place.dto.PlaceView;
import com.wander.place.dto.TripDay;
import com.wander.place.dto.TripItinerary;
import com.wander.place.dto.UpdatePlaceRequest;
import com.wander.trip.Trip;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripMember;
import com.wander.trip.TripRole;
import com.wander.trip.dto.TripSummary;

/**
 * Reads go through {@code requireMember}, writes through {@code requireRole} —
 * every entry point starts with one of the two, so there is no path to a place
 * that skips the membership check.
 *
 * This service also owns ordering. Ranks are dense and zero-based, and every
 * write renumbers the affected day(s) from scratch rather than trying to patch
 * individual ranks: it is one extra pass over a list that is never long, and it
 * cannot leave gaps or duplicates behind.
 */
@Service
public class PlaceService {

    /** Who may change trip content. VIEWER is absent on purpose. */
    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    private final PlaceRepository places;
    private final TripAccessService access;

    public PlaceService(PlaceRepository places, TripAccessService access) {
        this.places = places;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public TripItinerary itinerary(Long userId, Long tripId) {
        TripMember member = access.requireMember(tripId, userId);
        Trip trip = member.getTrip();

        Map<LocalDate, List<PlaceView>> byDay = places.findByTripIdOrderByDayDateAscSortOrderAsc(tripId)
                .stream()
                .map(PlaceView::of)
                .collect(Collectors.groupingBy(PlaceView::dayDate, java.util.LinkedHashMap::new,
                        Collectors.toList()));

        List<TripDay> days = new ArrayList<>(trip.dayCount());
        for (int i = 0; i < trip.dayCount(); i++) {
            LocalDate date = trip.getStartDate().plusDays(i);
            days.add(new TripDay(date, i + 1, byDay.getOrDefault(date, List.of())));
        }
        return new TripItinerary(TripSummary.of(trip, member.getRole()), days);
    }

    @Transactional
    public PlaceView create(Long userId, Long tripId, CreatePlaceRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        Trip trip = member.getTrip();
        requireDayInTrip(trip, request.dayDate());

        int end = places.findByTripIdAndDayDateOrderBySortOrderAsc(tripId, request.dayDate()).size();
        Place place = new Place(trip, request.dayDate(), end, request.name(),
                blankToNull(request.notes()));
        // Rejects half a point with a 400 rather than letting the database
        // CHECK turn it into a 500.
        place.setLocation(request.latitude(), request.longitude(), blankToNull(request.address()));
        return PlaceView.of(places.save(place));
    }

    @Transactional
    public PlaceView update(Long userId, Long tripId, Long placeId, UpdatePlaceRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Place place = require(tripId, placeId);
        place.setName(request.name());
        place.setNotes(blankToNull(request.notes()));
        return PlaceView.of(place);
    }

    @Transactional
    public PlaceView move(Long userId, Long tripId, Long placeId, MovePlaceRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        requireDayInTrip(member.getTrip(), request.dayDate());

        Place place = require(tripId, placeId);
        LocalDate from = place.getDayDate();
        LocalDate to = request.dayDate();

        // Pull it out of its current day and renumber what is left behind.
        List<Place> source = mutableDay(tripId, from);
        source.removeIf(candidate -> candidate.getId().equals(placeId));

        // Within one day, source is already the post-removal list; across days it
        // is the other day that needs the insert.
        List<Place> target = from.equals(to) ? source : mutableDay(tripId, to);
        // Clamp rather than reject: the client does not have to know the length.
        int index = Math.min(request.position(), target.size());
        place.setDayDate(to);
        target.add(index, place);

        renumber(source);
        if (!from.equals(to)) {
            renumber(target);
        }
        return PlaceView.of(place);
    }

    @Transactional
    public void delete(Long userId, Long tripId, Long placeId) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Place place = require(tripId, placeId);
        LocalDate day = place.getDayDate();
        places.delete(place);
        places.flush();
        // The hole the delete left would otherwise make the next insert collide
        // with an existing rank.
        renumber(mutableDay(tripId, day));
    }

    private Place require(Long tripId, Long placeId) {
        return places.findByIdAndTripId(placeId, tripId)
                .orElseThrow(() -> new NotFoundException("Place " + placeId + " not found"));
    }

    private List<Place> mutableDay(Long tripId, LocalDate day) {
        return new ArrayList<>(places.findByTripIdAndDayDateOrderBySortOrderAsc(tripId, day));
    }

    private static void renumber(List<Place> day) {
        for (int i = 0; i < day.size(); i++) {
            day.get(i).setSortOrder(i);
        }
    }

    /**
     * A place outside the trip's range would belong to a day the client never
     * draws, so it would simply disappear.
     */
    private static void requireDayInTrip(Trip trip, LocalDate day) {
        if (day.isBefore(trip.getStartDate()) || day.isAfter(trip.getEndDate())) {
            throw new IllegalArgumentException("dayDate must fall between " + trip.getStartDate()
                    + " and " + trip.getEndDate());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
