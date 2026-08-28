package com.wander.day;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DayNoteRepository extends JpaRepository<DayNote, Long> {

    /** Every note of a trip, for the one query the itinerary makes. */
    List<DayNote> findByTripId(Long tripId);

    /**
     * Always with the trip id: the pair is the natural key, and it is also what
     * keeps another trip's note out of reach.
     */
    Optional<DayNote> findByTripIdAndDayDate(Long tripId, LocalDate dayDate);
}
