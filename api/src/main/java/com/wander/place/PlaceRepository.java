package com.wander.place;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /** The whole itinerary of a trip in render order — one query per page. */
    List<Place> findByTripIdOrderByDayDateAscSortOrderAsc(Long tripId);

    /** One day's places, in order. The list the service renumbers. */
    List<Place> findByTripIdAndDayDateOrderBySortOrderAsc(Long tripId, LocalDate dayDate);

    /**
     * Always looked up with the trip id, never by place id alone: that is what
     * keeps a place id from another trip out of reach even for a member of this
     * one.
     */
    Optional<Place> findByIdAndTripId(Long id, Long tripId);
}
