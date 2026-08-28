package com.wander.expense.dto;

import java.util.List;

import com.wander.trip.dto.TripSummary;

import jakarta.validation.constraints.NotNull;

/**
 * Everything the expenses page needs, in one request — the same shape as
 * `TripItinerary`, and for the same reason. The trip comes along because the page
 * needs its name, its currency and the caller's role to decide what to draw.
 */
public record TripExpenses(
        @NotNull TripSummary trip,
        @NotNull ExpenseSummary summary,
        @NotNull List<ExpenseView> expenses) {
}
