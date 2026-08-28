package com.wander.expense;

/**
 * Whether a row is something the trip spent, or somebody settling up.
 *
 * Both live in `expenses` because they are the same arithmetic: a payment is
 * "one person paid, one person owes it", which is exactly what an expense with a
 * single share is. The distinction exists for one reason — a trip's total is what
 * it cost, and money moving between its members is not a cost.
 */
public enum ExpenseKind {
    /** Something was bought. Counts towards the trip total. */
    EXPENSE,
    /** Somebody paid somebody else back. Moves balances, costs nothing. */
    PAYMENT
}
