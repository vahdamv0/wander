package com.wander.trip;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<Trip, Long> {

    /**
     * Scoped by membership in the query itself rather than filtered afterwards,
     * so "list trips" can never leak one the caller is not a member of.
     */
    @Query("""
            select t from Trip t
            where exists (select 1 from TripMember m where m.trip = t and m.user.id = :userId)
            order by t.startDate desc, t.id desc
            """)
    List<Trip> findAllForUser(@Param("userId") Long userId);

    @Query("""
            select t from Trip t
            where exists (select 1 from TripMember m
                          where m.trip = t and m.user.id = :userId and m.role = :role)
            order by t.id asc
            """)
    List<Trip> findAllOwnedBy(@Param("userId") Long userId, @Param("role") TripRole role);
}
