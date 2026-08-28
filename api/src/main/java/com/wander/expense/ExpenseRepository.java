package com.wander.expense;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /**
     * Newest first, which is how the page reads. `spentOn` alone is not a total
     * order — several things get paid on one day — so `id` breaks the tie and
     * keeps the list stable between requests.
     *
     * The fetch join is what stops this being N+1 over the shares: every expense
     * carries its whole split, and the page shows all of them.
     */
    @Query("""
            select distinct e from Expense e
            left join fetch e.shares s
            left join fetch s.user
            join fetch e.paidBy
            where e.trip.id = :tripId
            order by e.spentOn desc, e.id desc
            """)
    List<Expense> findForTrip(Long tripId);

    Optional<Expense> findByIdAndTripId(Long id, Long tripId);
}
