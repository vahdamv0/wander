package com.wander.trip;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TripInviteRepository extends JpaRepository<TripInvite, Long> {

    /**
     * The only way a token is ever looked up: by its digest.
     *
     * The token itself is never stored, so this is also the only thing that could
     * find an invitation from a link somebody was sent.
     */
    Optional<TripInvite> findByTokenHash(String tokenHash);

    /**
     * The same lookup, holding a row lock, for accepting.
     *
     * Single use has to survive two people clicking one link at the same moment —
     * a couple forwarded the same message, or somebody double-clicked. Without the
     * lock both transactions read an unused invitation, both add a member, and
     * both mark it used; the link admits two people and the count of members is
     * whatever the interleaving produced. With it, the second waits, then sees
     * `accepted_at` already set and is refused.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from TripInvite i where i.tokenHash = :tokenHash")
    Optional<TripInvite> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<TripInvite> findAllByTripIdOrderByCreatedAtDesc(Long tripId);

    Optional<TripInvite> findByIdAndTripId(Long id, Long tripId);
}
