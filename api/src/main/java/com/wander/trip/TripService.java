package com.wander.trip;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.ConflictException;
import com.wander.common.NotFoundException;
import com.wander.config.WanderProperties;
import com.wander.day.DayNote;
import com.wander.day.DayNoteRepository;
import com.wander.expense.ExpenseRepository;
import com.wander.place.Place;
import com.wander.place.PlaceRepository;
import com.wander.sync.TripChanges;
import com.wander.trip.dto.CreateTripRequest;
import com.wander.trip.dto.TripSummary;
import com.wander.trip.dto.UpdateTripRequest;
import com.wander.user.User;
import com.wander.user.UserRepository;

@Service
public class TripService {

    private final TripRepository trips;
    private final TripMemberRepository members;
    private final UserRepository users;
    private final TripAccessService access;
    private final TripChanges changes;
    private final WanderProperties properties;
    private final PlaceRepository places;
    private final DayNoteRepository notes;
    private final ExpenseRepository expenses;

    public TripService(TripRepository trips, TripMemberRepository members, UserRepository users,
            TripAccessService access, TripChanges changes, WanderProperties properties,
            PlaceRepository places, DayNoteRepository notes, ExpenseRepository expenses) {
        this.trips = trips;
        this.members = members;
        this.users = users;
        this.access = access;
        this.changes = changes;
        this.properties = properties;
        this.places = places;
        this.notes = notes;
        this.expenses = expenses;
    }

    @Transactional
    public TripSummary create(Long userId, CreateTripRequest request) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        User creator = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));

        // Omitted means "whatever this instance uses"; it is fixed from here on,
        // because the expense amounts stored against it will mean something.
        String currency = request.currency() == null || request.currency().isBlank()
                ? properties.currency()
                : request.currency();

        Trip trip = trips.save(new Trip(request.name(), request.destination(), request.startDate(),
                request.endDate(), currency));
        // Creating the trip and the owner membership in one transaction: a trip
        // with no members would be invisible to everyone, including its author.
        members.save(new TripMember(trip, creator, TripRole.OWNER));
        return TripSummary.of(trip, TripRole.OWNER);
    }

    @Transactional(readOnly = true)
    public List<TripSummary> listForUser(Long userId) {
        List<Trip> visible = trips.findAllForUser(userId);
        // One membership lookup for the whole page rather than per trip.
        Map<Long, TripRole> roles = visible.stream()
                .map(trip -> members.findByTripIdAndUserId(trip.getId(), userId).orElseThrow())
                .collect(Collectors.toMap(m -> m.getTrip().getId(), TripMember::getRole,
                        (a, b) -> a));
        return visible.stream()
                .map(trip -> TripSummary.of(trip, roles.get(trip.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TripSummary get(Long userId, Long tripId) {
        TripMember member = access.requireMember(tripId, userId);
        return TripSummary.of(member.getTrip(), member.getRole());
    }

    /**
     * Rewrites a trip. Owner only, like deleting one: renaming is harmless, but
     * moving the dates can invalidate everybody else's work, so it sits with the
     * one member who cannot be removed.
     *
     * The date range is the whole difficulty. Days are *derived* from it, so
     * places and day notes carry a plain date and nothing in the database stops
     * one of them referring to a day the trip no longer has. Two cases, and the
     * distinction matters more than it looks:
     *
     *  - **Same length, different dates** — "we moved the trip a week later". The
     *    itinerary moves with it, by the same offset. Refusing this would be
     *    absurd: the common edit would be impossible on any trip that had content.
     *  - **Any other change that would leave content outside the new range** —
     *    refused, naming what is in the way. The alternatives are to hide those
     *    rows (a trip holding places nobody can see) or delete them (destroying a
     *    collaborator's work from a date picker). Both are worse than being told
     *    to move them first.
     */
    @Transactional
    public TripSummary update(Long userId, Long tripId, UpdateTripRequest request) {
        TripMember member = access.requireRole(tripId, userId, TripRole.OWNER);
        Trip trip = member.getTrip();
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }

        LocalDate oldStart = trip.getStartDate();
        long oldLength = ChronoUnit.DAYS.between(oldStart, trip.getEndDate());
        long newLength = ChronoUnit.DAYS.between(request.startDate(), request.endDate());
        long offset = ChronoUnit.DAYS.between(oldStart, request.startDate());

        if (oldLength == newLength && offset != 0) {
            shiftItinerary(tripId, offset);
        } else if (oldLength != newLength) {
            requireNothingOrphaned(tripId, request.startDate(), request.endDate());
        }

        if (request.currency() != null && !request.currency().equals(trip.getCurrency())) {
            if (expenses.existsByTripId(tripId)) {
                throw new ConflictException(
                        "This trip already has expenses counted in " + trip.getCurrency()
                                + "; its currency cannot be changed");
            }
            trip.setCurrency(request.currency());
        }

        trip.setName(request.name());
        trip.setDestination(request.destination());
        trip.setDates(request.startDate(), request.endDate());

        // The day list, every place's date and the trip's own name all just moved.
        changes.itineraryChanged(tripId, userId);
        return TripSummary.of(trip, member.getRole());
    }

    /** Moves every place and day note by the same number of days. */
    private void shiftItinerary(Long tripId, long offset) {
        for (Place place : places.findByTripIdOrderByDayDateAscSortOrderAsc(tripId)) {
            place.setDayDate(place.getDayDate().plusDays(offset));
        }

        // Notes are deleted and reinserted rather than updated in place, because
        // `uq_day_notes_trip_day` is not deferrable: shifting a note from the 12th
        // to the 13th while the 13th still holds one collides mid-statement,
        // whatever order the updates are issued in. Places have no such
        // constraint — V2 left it out on purpose for the same reason.
        //
        // A note's identity is (trip, date), not its id, so new rows are not a
        // loss: nothing outside this table refers to one by id.
        List<DayNote> existing = notes.findByTripId(tripId);
        if (existing.isEmpty()) {
            return;
        }
        Map<LocalDate, String> byDay = new LinkedHashMap<>();
        Trip trip = existing.get(0).getTrip();
        for (DayNote note : existing) {
            byDay.put(note.getDayDate().plusDays(offset), note.getNote());
        }
        notes.deleteAll(existing);
        notes.flush();
        List<DayNote> shifted = new ArrayList<>(byDay.size());
        byDay.forEach((date, text) -> shifted.add(new DayNote(trip, date, text)));
        notes.saveAll(shifted);
    }

    /**
     * Refuses a range change that would strand content, saying what and where.
     *
     * The count is the useful part of the message: "3 places" tells somebody
     * whether they are about to lose an afternoon's planning or a stray idea.
     */
    private void requireNothingOrphaned(Long tripId, LocalDate start, LocalDate end) {
        long strandedPlaces = places.findByTripIdOrderByDayDateAscSortOrderAsc(tripId).stream()
                .filter(place -> outside(place.getDayDate(), start, end))
                .count();
        long strandedNotes = notes.findByTripId(tripId).stream()
                .filter(note -> outside(note.getDayDate(), start, end))
                .count();
        if (strandedPlaces == 0 && strandedNotes == 0) {
            return;
        }

        List<String> parts = new ArrayList<>();
        if (strandedPlaces > 0) {
            parts.add(strandedPlaces + (strandedPlaces == 1 ? " place" : " places"));
        }
        if (strandedNotes > 0) {
            parts.add(strandedNotes + (strandedNotes == 1 ? " note" : " notes"));
        }
        // The verb agrees with the whole subject, not with the last noun in it:
        // "1 place falls outside", "2 places and 1 note fall outside".
        boolean single = strandedPlaces + strandedNotes == 1;
        throw new ConflictException(String.join(" and ", parts)
                + (single ? " falls outside " : " fall outside ") + start + " to " + end
                + (single ? ". Move or delete it first." : ". Move or delete them first."));
    }

    private static boolean outside(LocalDate day, LocalDate start, LocalDate end) {
        return day.isBefore(start) || day.isAfter(end);
    }

    @Transactional
    public void delete(Long userId, Long tripId) {
        // Owner only; any other member gets 403, a non-member 404.
        TripMember member = access.requireRole(tripId, userId, TripRole.OWNER);
        // Memberships go with it: trip_members declares ON DELETE CASCADE, so
        // deleting rows here first would just duplicate what the database does.
        trips.delete(member.getTrip());
        // Tells the other members' open pages before hanging up on them.
        changes.tripDeleted(tripId, userId);
    }
}
