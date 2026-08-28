package com.wander.expense;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wander.expense.dto.ExpenseRequest;
import com.wander.expense.dto.ExpenseView;
import com.wander.expense.dto.PaymentRequest;
import com.wander.expense.dto.TripExpenses;
import com.wander.security.WanderUser;

import jakarta.validation.Valid;

/**
 * The trip's ledger. `listExpenses` returns the summary along with the list, so
 * the page is one request — the same bargain `getItinerary` makes.
 *
 * Method names are the operation ids and the generated client exports them
 * unqualified, hence `createExpense` rather than `create`.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/expenses")
public class ExpenseController {

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping
    public TripExpenses listExpenses(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId) {
        return expenses.list(principal.id(), tripId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView createExpense(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @Valid @RequestBody ExpenseRequest request) {
        return expenses.create(principal.id(), tripId, request);
    }

    /**
     * Records a payment between two members. Under /expenses because it is a row
     * in the same ledger — it appears in `listExpenses` and is removed with
     * `deleteExpense`, and only its creation needs a shape of its own.
     */
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView recordPayment(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @Valid @RequestBody PaymentRequest request) {
        return expenses.recordPayment(principal.id(), tripId, request);
    }

    @PutMapping("/{expenseId}")
    public ExpenseView updateExpense(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long expenseId,
            @Valid @RequestBody ExpenseRequest request) {
        return expenses.update(principal.id(), tripId, expenseId, request);
    }

    @DeleteMapping("/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExpense(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long expenseId) {
        expenses.delete(principal.id(), tripId, expenseId);
    }
}
