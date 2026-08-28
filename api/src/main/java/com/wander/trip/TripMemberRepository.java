package com.wander.trip;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TripMemberRepository extends JpaRepository<TripMember, Long> {

    Optional<TripMember> findByTripIdAndUserId(Long tripId, Long userId);

    /** Joining order, so the owner (created with the trip) reads first. */
    List<TripMember> findAllByTripIdOrderByJoinedAtAsc(Long tripId);
}
