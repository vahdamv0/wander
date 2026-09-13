package com.wander.place;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;
import com.wander.day.DayNote;
import com.wander.day.DayNoteRepository;
import com.wander.expense.ExpenseRepository;
import com.wander.place.dto.CreatePlaceRequest;
import com.wander.place.dto.LockPlaceRequest;
import com.wander.place.dto.MovePlaceRequest;
import com.wander.place.dto.ReorderDayRequest;
import com.wander.place.dto.PlaceView;
import com.wander.place.dto.TripDay;
import com.wander.place.dto.TripItinerary;
import com.wander.place.dto.UpdatePlaceRequest;
import com.wander.sync.TripChanges;
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
    private final DayNoteRepository notes;
    private final ExpenseRepository expenses;
    private final TripAccessService access;
    private final TripChanges changes;

    public PlaceService(PlaceRepository places, DayNoteRepository notes, ExpenseRepository expenses,
            TripAccessService access, TripChanges changes) {
        this.places = places;
        this.notes = notes;
        this.expenses = expenses;
        this.access = access;
        this.changes = changes;
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

        // The day notes of the whole trip in one query, for the same reason the
        // places come in one: the page is a single request.
        Map<LocalDate, String> notesByDay = notes.findByTripId(tripId).stream()
                .collect(Collectors.toMap(DayNote::getDayDate, DayNote::getNote));

        // And what each day cost, in the same one-query-for-the-trip shape. The
        // expenses page owns the money; this is the one number from it that
        // belongs on a day, because "we spent a lot on the day we did nothing" is
        // a thing you only notice with the two side by side.
        Map<LocalDate, Long> spentByDay = expenses.sumPerDay(tripId).stream()
                .collect(Collectors.toMap(row -> (LocalDate) row[0], row -> (Long) row[1]));

        List<TripDay> days = new ArrayList<>(trip.dayCount());
        for (int i = 0; i < trip.dayCount(); i++) {
            LocalDate date = trip.getStartDate().plusDays(i);
            days.add(new TripDay(date, i + 1, byDay.getOrDefault(date, List.of()),
                    notesByDay.get(date), spentByDay.get(date)));
        }
        return new TripItinerary(TripSummary.of(trip, member.getRole()), days);
    }

    @Transactional
    public PlaceView create(Long userId, Long tripId, CreatePlaceRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        Trip trip = member.getTrip();
        trip.requireCovers(request.dayDate());

        int end = places.findByTripIdAndDayDateOrderBySortOrderAsc(tripId, request.dayDate()).size();
        Place place = new Place(trip, request.dayDate(), end, request.name());
        place.replaceNotes(cleaned(request.notes()));
        place.setStartsAt(request.startsAt());
        // Rejects half a point with a 400 rather than letting the database
        // CHECK turn it into a 500.
        place.setLocation(request.latitude(), request.longitude(), blankToNull(request.address()));
        // Kept whether or not anything reads it yet: a place saved without it can
        // never be enriched, and there is no way to work it out afterwards.
        place.setOsmRef(blankToNull(request.osmRef()));
        place.setCategory(blankToNull(request.category()));
        Place saved = places.save(place);
        // Announced, not sent: the event fires after this transaction commits and
        // says only that the itinerary moved. See TripChange.
        changes.itineraryChanged(tripId, userId);
        return PlaceView.of(saved);
    }

    @Transactional
    public PlaceView update(Long userId, Long tripId, Long placeId, UpdatePlaceRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Place place = require(tripId, placeId);
        place.setName(request.name());
        // Written whole. A blank body is dropped rather than stored, so emptying a
        // box is how a note is removed — the same meaning a day note gives it.
        place.replaceNotes(cleaned(request.notes()));
        place.setStartsAt(request.startsAt());
        changes.itineraryChanged(tripId, userId);
        return PlaceView.of(place);
    }

    @Transactional
    public PlaceView move(Long userId, Long tripId, Long placeId, MovePlaceRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        member.getTrip().requireCovers(request.dayDate());

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
        changes.itineraryChanged(tripId, userId);
        return PlaceView.of(place);
    }

    /**
     * The whole of one day, in the order given.
     *
     * This is how a route proposal is applied, and it deliberately knows
     * nothing about routes: it takes ids and assigns ranks, so there is one
     * place in this application that decides what order a day is in, whether
     * the order came from a drag, an arrow key or an optimiser.
     *
     * The id set must match the day exactly. A list missing one of them would
     * need a rule for where the missing place goes, and any such rule turns a
     * half-applied reorder into something that looks like it worked — so it is
     * a 400 instead. It also means a proposal made against a day somebody else
     * has since added a place to is refused rather than silently dropping
     * their place to the end.
     *
     * A locked place must still be where it was. Locking is what tells an
     * optimiser to leave a stop alone, so a client that ignored it and posted
     * the order anyway would make the lock a suggestion; a person who wants it
     * elsewhere drags it, which unlocks nothing and needs no permission.
     */
    @Transactional
    public List<PlaceView> reorderDay(Long userId, Long tripId, LocalDate dayDate,
            ReorderDayRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);

        List<Place> day = mutableDay(tripId, dayDate);
        Map<Long, Place> byId = day.stream()
                .collect(Collectors.toMap(Place::getId, place -> place));
        List<Long> wanted = request.placeIds();
        if (wanted.size() != day.size() || !byId.keySet().equals(Set.copyOf(wanted))) {
            throw new IllegalArgumentException(
                    "The order must list every place on this day exactly once."
                            + " Reload the day and try again.");
        }

        List<Place> reordered = wanted.stream().map(byId::get).toList();
        for (int i = 0; i < reordered.size(); i++) {
            Place place = reordered.get(i);
            if (place.isLocked() && place.getSortOrder() != i) {
                throw new IllegalArgumentException(
                        "\"" + place.getName() + "\" is locked to its position.");
            }
        }
        renumber(reordered);
        changes.itineraryChanged(tripId, userId);
        return reordered.stream().map(PlaceView::of).toList();
    }

    /**
     * Locking, on its own endpoint for the reason ticking a packing item has
     * one: it is a single flag, and carrying the rest of the place along to
     * flip it lets a lock undo somebody's rename.
     */
    @Transactional
    public PlaceView setLocked(Long userId, Long tripId, Long placeId, LockPlaceRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Place place = require(tripId, placeId);
        place.setLocked(request.locked());
        changes.itineraryChanged(tripId, userId);
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
        changes.itineraryChanged(tripId, userId);
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
     * The bodies worth storing: trimmed, with the blanks dropped.
     *
     * A form with three boxes and one filled in should not leave two empty notes
     * behind, and "no notes" is the absence of rows rather than rows holding
     * nothing — the same rule `day_notes` follows.
     */
    private static List<String> cleaned(List<String> bodies) {
        if (bodies == null) {
            return List.of();
        }
        return bodies.stream()
                .filter(body -> body != null && !body.isBlank())
                .map(String::strip)
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
