package com.wander.day;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.day.dto.DayNoteRequest;
import com.wander.day.dto.DayNoteView;
import com.wander.sync.TripChanges;
import com.wander.trip.Trip;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripMember;
import com.wander.trip.TripRole;

/**
 * One note per day, written whole.
 *
 * The write is an upsert, and a blank note is a delete: "no note" is the absence
 * of a row, so the endpoint has one meaning of empty and the client needs no
 * second call to clear one.
 */
@Service
public class DayNoteService {

    /** Same rule as places: viewers read the itinerary but write nothing. */
    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    private final DayNoteRepository notes;
    private final TripAccessService access;
    private final TripChanges changes;

    public DayNoteService(DayNoteRepository notes, TripAccessService access, TripChanges changes) {
        this.notes = notes;
        this.access = access;
        this.changes = changes;
    }

    @Transactional
    public DayNoteView put(Long userId, Long tripId, LocalDate dayDate, DayNoteRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        Trip trip = member.getTrip();
        trip.requireCovers(dayDate);

        // A note rides along on the itinerary, so a change to one is an itinerary
        // change as far as anybody watching is concerned.
        changes.itineraryChanged(tripId, userId);

        String text = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (text == null) {
            notes.findByTripIdAndDayDate(tripId, dayDate).ifPresent(notes::delete);
            return DayNoteView.empty(dayDate);
        }

        DayNote note = notes.findByTripIdAndDayDate(tripId, dayDate).orElse(null);
        if (note == null) {
            note = notes.save(new DayNote(trip, dayDate, text));
        } else {
            note.setNote(text);
        }
        return DayNoteView.of(note);
    }
}
