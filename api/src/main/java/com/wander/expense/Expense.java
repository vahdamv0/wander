package com.wander.expense;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.wander.trip.Trip;
import com.wander.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * One thing somebody paid for.
 *
 * The amount is in the trip's currency, in **minor units** — 1234 is 12.34. That
 * is the only representation anywhere in this feature: no BigDecimal, no double,
 * no strings that look like money. Integer arithmetic cannot lose a cent, and a
 * balance is then just addition.
 *
 * `spentOn` is not checked against the trip's date range, unlike a place's day:
 * flights and deposits are paid months before anybody travels.
 */
@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(nullable = false)
    private String description;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paid_by_user_id", nullable = false)
    private User paidBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_mode", nullable = false)
    private SplitMode splitMode;

    /**
     * The shares are part of the expense, not a separate thing that happens to
     * point at it: an expense with no shares is meaningless, and replacing a
     * split means replacing all of them at once. Hence cascade-all with
     * orphan removal, which is also what makes `expenses.delete()` take the
     * shares with it without a second repository call.
     */
    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<ExpenseShare> shares = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Expense() {
        // JPA
    }

    public Expense(Trip trip, String description, long amountMinor, LocalDate spentOn, User paidBy,
            SplitMode splitMode) {
        this.trip = trip;
        this.description = description;
        this.amountMinor = amountMinor;
        this.spentOn = spentOn;
        this.paidBy = paidBy;
        this.splitMode = splitMode;
        this.createdAt = Instant.now();
    }

    /**
     * Replaces the whole split. Callers hand over resolved minor-unit amounts —
     * the arithmetic has already happened in {@link ExpenseSplitter} and been
     * checked against the total, so nothing here can invent money.
     *
     * Rows for participants who are still in the split are **updated, not
     * replaced**, and that is not an optimisation. `clear()` followed by adding
     * everything back makes Hibernate order the inserts before the orphan
     * deletes inside one flush, and `uq_expense_shares (expense_id, user_id)`
     * then rejects the write with a 500 for anybody who was in both the old split
     * and the new one — which is almost everybody, almost every time somebody
     * corrects an amount.
     */
    public void replaceShares(java.util.Map<Long, Long> amountsByUserId,
            java.util.function.LongFunction<User> users) {
        shares.removeIf(share -> !amountsByUserId.containsKey(share.getUser().getId()));
        for (ExpenseShare share : shares) {
            share.setAmountMinor(amountsByUserId.get(share.getUser().getId()));
        }

        java.util.Set<Long> alreadyThere = shares.stream()
                .map(share -> share.getUser().getId())
                .collect(java.util.stream.Collectors.toSet());
        amountsByUserId.forEach((userId, amount) -> {
            if (!alreadyThere.contains(userId)) {
                shares.add(new ExpenseShare(this, users.apply(userId), amount));
            }
        });
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public void setAmountMinor(long amountMinor) {
        this.amountMinor = amountMinor;
    }

    public LocalDate getSpentOn() {
        return spentOn;
    }

    public void setSpentOn(LocalDate spentOn) {
        this.spentOn = spentOn;
    }

    public User getPaidBy() {
        return paidBy;
    }

    public void setPaidBy(User paidBy) {
        this.paidBy = paidBy;
    }

    public SplitMode getSplitMode() {
        return splitMode;
    }

    public void setSplitMode(SplitMode splitMode) {
        this.splitMode = splitMode;
    }

    public List<ExpenseShare> getShares() {
        return shares;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
