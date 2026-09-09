package com.wander.expense;

import java.math.BigDecimal;
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
 * That stayed true when expenses learned about other currencies, and it is the
 * point of how they did. `amountMinor` still means the trip's currency and is
 * still what every aggregate sums; an expense paid in yen is converted **once,
 * on the way in**, and what is kept beside it is the receipt — the original
 * amount, its currency, and the rate that was applied, frozen. So the balances
 * never mix currencies and never need a rate to be computed, which is what `V6`
 * meant by "balances that mix currencies stop being arithmetic".
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
     * An expense, or somebody settling up. A payment is stored here rather than
     * in a table of its own because it is the same arithmetic — see
     * {@link ExpenseKind} and `V7`.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ExpenseKind kind = ExpenseKind.EXPENSE;

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

    /**
     * What was actually handed over, when it was not in the trip's currency, and
     * the rate that turned it into {@code amountMinor}.
     *
     * All five move together or not at all — see {@link #setConversion} — and all
     * five being null is the ordinary case: an expense paid in the trip's own
     * currency has no conversion to describe, which is also why this needed no
     * backfill.
     *
     * The source amount is kept because it is the number on the receipt. An
     * expenses page that showed only the converted figure could not be checked
     * against a bank statement, which is most of what anybody does with a ledger
     * once the trip is over.
     */
    @Column(name = "source_amount_minor")
    private Long sourceAmountMinor;

    @Column(name = "source_currency", length = 3)
    private String sourceCurrency;

    /**
     * **Frozen at entry, and never looked up again.** The money left the account
     * at the rate of the day, and no later rate makes that untrue — so correcting
     * a typo in a description must not be able to move somebody's balance. The
     * only things that replace it are the two that make it a different claim: a
     * change of currency, or a change of the date it was spent on.
     */
    @Column(name = "fx_rate", precision = 30, scale = 15)
    private BigDecimal fxRate;

    @Column(name = "fx_quoted_on")
    private LocalDate fxQuotedOn;

    /** True when a person typed the rate rather than it being looked up. */
    @Column(name = "fx_manual", nullable = false)
    private boolean fxManual;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Expense() {
        // JPA
    }

    public Expense(Trip trip, String description, long amountMinor, LocalDate spentOn, User paidBy,
            SplitMode splitMode, ExpenseKind kind) {
        this.trip = trip;
        this.description = description;
        this.amountMinor = amountMinor;
        this.spentOn = spentOn;
        this.paidBy = paidBy;
        this.splitMode = splitMode;
        this.kind = kind;
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

    /**
     * Records that this expense was paid in another currency, all five columns at
     * once or not at all.
     *
     * Shaped like {@code Place.setPhoto}, and for the same kind of reason: a
     * photo without its licence is a picture this project has no right to draw,
     * and a source amount without its rate is a number nothing can interpret.
     * Neither is a set of independent fields, so neither gets independent
     * setters — the database says the same thing in {@code ck_expenses_fx}, and
     * agreeing with it here is what stops a service writing half a conversion.
     *
     * {@code quotedOn} is null for a rate somebody typed. There is no publication
     * date to name, and inventing one would dress a person's own figure up as a
     * market quote.
     */
    public void setConversion(String sourceCurrency, long sourceAmountMinor, BigDecimal rate,
            LocalDate quotedOn, boolean manual) {
        if (sourceCurrency == null || rate == null || rate.signum() <= 0 || sourceAmountMinor <= 0) {
            throw new IllegalArgumentException("A conversion needs a currency, an amount and a positive rate");
        }
        this.sourceCurrency = sourceCurrency;
        this.sourceAmountMinor = sourceAmountMinor;
        this.fxRate = rate;
        this.fxQuotedOn = quotedOn;
        this.fxManual = manual;
    }

    /** Back to an expense in the trip's own currency, which is the absence of all five. */
    public void clearConversion() {
        this.sourceCurrency = null;
        this.sourceAmountMinor = null;
        this.fxRate = null;
        this.fxQuotedOn = null;
        this.fxManual = false;
    }

    /** True when this was paid in something other than the trip's currency. */
    public boolean isConverted() {
        return sourceCurrency != null;
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public String getSourceCurrency() {
        return sourceCurrency;
    }

    public Long getSourceAmountMinor() {
        return sourceAmountMinor;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public LocalDate getFxQuotedOn() {
        return fxQuotedOn;
    }

    public boolean isFxManual() {
        return fxManual;
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

    public ExpenseKind getKind() {
        return kind;
    }

    /** True for money that moved between members rather than out of the trip. */
    public boolean isPayment() {
        return kind == ExpenseKind.PAYMENT;
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
