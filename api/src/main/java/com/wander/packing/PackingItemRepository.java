package com.wander.packing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PackingItemRepository extends JpaRepository<PackingItem, Long> {

    /**
     * A trip's items in the order they were added. The joins are what stop this
     * being N+1 over the assignee and the person who packed each one — the page
     * shows both for every item it draws.
     */
    @Query("""
            select i from PackingItem i
            left join fetch i.assignee
            left join fetch i.packedBy
            where i.trip.id = :tripId
            order by i.id asc
            """)
    List<PackingItem> findForTrip(Long tripId);

    Optional<PackingItem> findByIdAndTripId(Long id, Long tripId);
}
