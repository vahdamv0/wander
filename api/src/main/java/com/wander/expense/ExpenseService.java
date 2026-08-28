package com.wander.expense;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;
import com.wander.expense.dto.ExpenseRequest;
import com.wander.expense.dto.ExpenseShareInput;
import com.wander.expense.dto.ExpenseSummary;
import com.wander.expense.dto.ExpenseView;
import com.wander.expense.dto.PersonBalance;
import com.wander.expense.dto.SettlementView;
import com.wander.expense.dto.TripExpenses;
import com.wander.sync.TripChanges;
import com.wander.trip.Trip;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripMember;
import com.wander.trip.TripMemberRepository;
import com.wander.trip.TripRole;
import com.wander.trip.dto.TripSummary;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * Expenses, splits and balances.
 *
 * Reads go through `requireMember` and writes through `requireRole`, like every
 * other trip-scoped service. Any editor may edit any expense, the same way the
 * itinerary is collaboratively editable — an expense belongs to the trip, not to
 * whoever typed it in.
 *
 * The invariant everything else depends on: **the shares of an expense sum to
 * its amount, exactly.** For an EQUAL split the server computes them and that is
 * true by construction; for an EXACT split the client supplies them and this
 * service refuses the write unless they add up. Balances are then a sum over one
 * column and cannot drift.
 */
@Service
public class ExpenseService {

    /** Same rule as places and notes: a viewer reads the ledger and writes nothing. */
    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    private final ExpenseRepository expenses;
    private final TripMemberRepository members;
    private final UserRepository users;
    private final TripAccessService access;
    private final TripChanges changes;

    public ExpenseService(ExpenseRepository expenses, TripMemberRepository members, UserRepository users,
            TripAccessService access, TripChanges changes) {
        this.expenses = expenses;
        this.members = members;
        this.users = users;
        this.access = access;
        this.changes = changes;
    }

    @Transactional(readOnly = true)
    public TripExpenses list(Long userId, Long tripId) {
        TripMember member = access.requireMember(tripId, userId);
        List<Expense> all = expenses.findForTrip(tripId);
        return new TripExpenses(TripSummary.of(member.getTrip(), member.getRole()),
                summarise(tripId, all),
                all.stream().map(ExpenseView::of).toList());
    }

    @Transactional
    public ExpenseView create(Long userId, Long tripId, ExpenseRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        Trip trip = member.getTrip();

        Expense expense = new Expense(trip, request.description().strip(), request.amountMinor(),
                request.spentOn(), requireMemberUser(tripId, request.paidByUserId(), "paidByUserId"),
                request.splitMode());
        applySplit(tripId, expense, request);
        Expense saved = expenses.save(expense);

        changes.expensesChanged(tripId, userId);
        return ExpenseView.of(saved);
    }

    @Transactional
    public ExpenseView update(Long userId, Long tripId, Long expenseId, ExpenseRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Expense expense = require(tripId, expenseId);

        expense.setDescription(request.description().strip());
        expense.setAmountMinor(request.amountMinor());
        expense.setSpentOn(request.spentOn());
        expense.setPaidBy(requireMemberUser(tripId, request.paidByUserId(), "paidByUserId"));
        expense.setSplitMode(request.splitMode());
        // Written whole: changing who was in the split changes everybody's share,
        // so there is nothing sensible to patch.
        applySplit(tripId, expense, request);

        changes.expensesChanged(tripId, userId);
        return ExpenseView.of(expense);
    }

    @Transactional
    public void delete(Long userId, Long tripId, Long expenseId) {
        access.requireRole(tripId, userId, CAN_EDIT);
        // The shares go with it: the association cascades with orphan removal, so
        // there is no second delete to forget.
        expenses.delete(require(tripId, expenseId));
        changes.expensesChanged(tripId, userId);
    }

    /**
     * Resolves the split to whole minor units and hangs it on the expense.
     *
     * Both modes end in the same place. EQUAL asks {@link ExpenseSplitter}, whose
     * result sums to the amount by construction. EXACT takes the client's numbers
     * and checks that they do — a mismatch is a 400, not a silently adjusted
     * total, because "the client said 30/30/29 for a 90 bill" is a bug in the
     * client and swallowing it would quietly corrupt every balance on the trip.
     */
    private void applySplit(Long tripId, Expense expense, ExpenseRequest request) {
        List<Long> participants = request.shares().stream().map(ExpenseShareInput::userId).distinct().toList();
        if (participants.size() != request.shares().size()) {
            throw new IllegalArgumentException("A participant appears twice in the split");
        }
        Map<Long, User> participantUsers = participants.stream()
                .collect(Collectors.toMap(id -> id, id -> requireMemberUser(tripId, id, "shares[].userId")));

        Map<Long, Long> amounts;
        if (request.splitMode() == SplitMode.EQUAL) {
            amounts = ExpenseSplitter.equalShares(request.amountMinor(), participants);
        } else {
            amounts = new LinkedHashMap<>();
            long total = 0;
            for (ExpenseShareInput share : request.shares()) {
                if (share.amountMinor() == null) {
                    throw new IllegalArgumentException("An exact split needs an amount for every participant");
                }
                amounts.put(share.userId(), share.amountMinor());
                total += share.amountMinor();
            }
            if (total != request.amountMinor()) {
                throw new IllegalArgumentException(
                        "The shares add up to " + total + " but the expense is " + request.amountMinor());
            }
        }
        expense.replaceShares(amounts, userId -> participantUsers.get(userId));
    }

    /**
     * Everybody's standing, plus the fewest payments that would clear it.
     *
     * Computed here rather than in the client: there should be exactly one
     * implementation of this arithmetic, and it should be the one with tests
     * around it.
     */
    private ExpenseSummary summarise(Long tripId, List<Expense> all) {
        Map<Long, Long> paid = new LinkedHashMap<>();
        Map<Long, Long> owed = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        long total = 0;

        for (Expense expense : all) {
            total += expense.getAmountMinor();
            paid.merge(expense.getPaidBy().getId(), expense.getAmountMinor(), Long::sum);
            names.putIfAbsent(expense.getPaidBy().getId(), expense.getPaidBy().getDisplayName());
            for (ExpenseShare share : expense.getShares()) {
                owed.merge(share.getUser().getId(), share.getAmountMinor(), Long::sum);
                names.putIfAbsent(share.getUser().getId(), share.getUser().getDisplayName());
            }
        }

        // Current members with nothing to their name still belong in the list, at
        // zero: "who is on this trip and where do they stand" is the question, and
        // an absent row reads as missing data rather than as nothing owed.
        Set<Long> currentMembers = new java.util.LinkedHashSet<>();
        for (TripMember member : members.findAllByTripIdOrderByJoinedAtAsc(tripId)) {
            currentMembers.add(member.getUser().getId());
            names.putIfAbsent(member.getUser().getId(), member.getUser().getDisplayName());
        }

        Map<Long, Long> nets = new LinkedHashMap<>();
        List<Long> everybody = new ArrayList<>(names.keySet());
        for (Long id : everybody) {
            if (currentMembers.contains(id) || paid.containsKey(id) || owed.containsKey(id)) {
                nets.put(id, paid.getOrDefault(id, 0L) - owed.getOrDefault(id, 0L));
            }
        }

        List<PersonBalance> balances = nets.entrySet().stream()
                .map(entry -> new PersonBalance(entry.getKey(), names.get(entry.getKey()),
                        paid.getOrDefault(entry.getKey(), 0L), owed.getOrDefault(entry.getKey(), 0L),
                        entry.getValue(), currentMembers.contains(entry.getKey())))
                // Most owed first, then most owing, so the two ends of the ledger
                // are the two ends of the list.
                .sorted(Comparator.comparingLong(PersonBalance::netMinor).reversed()
                        .thenComparing(PersonBalance::displayName))
                .toList();

        List<SettlementView> settlements = ExpenseSplitter.settle(nets).stream()
                .map(transfer -> new SettlementView(transfer.fromUserId(), names.get(transfer.fromUserId()),
                        transfer.toUserId(), names.get(transfer.toUserId()), transfer.amountMinor()))
                .toList();

        return new ExpenseSummary(total, balances, settlements);
    }

    private Expense require(Long tripId, Long expenseId) {
        return expenses.findByIdAndTripId(expenseId, tripId)
                .orElseThrow(() -> new NotFoundException("Expense " + expenseId + " not found"));
    }

    /**
     * A payer or participant has to be on the trip *now*.
     *
     * 400 rather than 404: the caller is a member and the id is part of their
     * request body, so this is a malformed request rather than a missing thing —
     * and answering 404 would make it an oracle for which user ids exist.
     */
    private User requireMemberUser(Long tripId, Long userId, String field) {
        return members.findByTripIdAndUserId(tripId, userId)
                .map(TripMember::getUser)
                .orElseThrow(() -> new IllegalArgumentException(
                        field + ": user " + userId + " is not a member of this trip"));
    }
}
