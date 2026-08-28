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

    /** Whether anything has been counted in this trip's currency yet. */
    boolean existsByTripId(Long tripId);

    /**
     * What the trip spent, per day, for the itinerary's day cards.
     *
     * Payments are excluded for the same reason the trip total excludes them:
     * money moving from one member to another is not a cost, and counting it
     * would make a day look expensive because somebody settled up on it.
     *
     * Grouped in the database rather than by reading every expense and summing
     * in Java — the itinerary is the most-read endpoint in the application and
     * this must not become a second full read of the expense table.
     *
     * Days with nothing spent are simply absent from the result; the caller
     * treats a missing day as no total rather than as zero, so a day card stays
     * quiet instead of announcing that nothing happened.
     */
    @Query("""
            select e.spentOn, sum(e.amountMinor) from Expense e
            where e.trip.id = :tripId and e.kind <> com.wander.expense.ExpenseKind.PAYMENT
            group by e.spentOn
            """)
    List<Object[]> sumPerDay(Long tripId);
}
