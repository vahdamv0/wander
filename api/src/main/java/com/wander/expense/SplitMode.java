package com.wander.expense;

/** How the amounts in `expense_shares` were arrived at. */
public enum SplitMode {
    /** Divided evenly over the participants, with the remainder spread one minor unit at a time. */
    EQUAL,
    /** Given per participant by the client, and required to sum to the amount. */
    EXACT
}
