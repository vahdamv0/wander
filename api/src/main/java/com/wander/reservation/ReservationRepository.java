package com.wander.reservation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * Soonest first. Ordering by the instant rather than by any local time is the
     * whole reason instants are stored: a 23:00 departure in Tokyo really does
     * come before an 08:00 one in London the next morning, and only the instants
     * say so.
     */
    List<Reservation> findByTripIdOrderByStartsAtAscIdAsc(Long tripId);

    Optional<Reservation> findByIdAndTripId(Long id, Long tripId);
}
