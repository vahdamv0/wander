package com.wander.expense;

import com.wander.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * What one participant owes for one expense, in minor units.
 *
 * An equal split and an exact split both end up here identically — the split
 * mode is how these numbers were *arrived at*, not a second way of storing them.
 * That is what keeps a balance a single sum over one column.
 */
@Entity
@Table(name = "expense_shares",
        uniqueConstraints = @UniqueConstraint(columnNames = { "expense_id", "user_id" }))
public class ExpenseShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    protected ExpenseShare() {
        // JPA
    }

    public ExpenseShare(Expense expense, User user, long amountMinor) {
        this.expense = expense;
        this.user = user;
        this.amountMinor = amountMinor;
    }

    public Long getId() {
        return id;
    }

    public Expense getExpense() {
        return expense;
    }

    public User getUser() {
        return user;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    /**
     * Only the amount is mutable. A share's expense and participant are its
     * identity — a different pair is a different row, which is what
     * `uq_expense_shares` says.
     */
    public void setAmountMinor(long amountMinor) {
        this.amountMinor = amountMinor;
    }
}
