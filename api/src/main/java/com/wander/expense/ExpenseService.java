package com.wander.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import com.wander.common.ConflictException;
import com.wander.expense.dto.ExpenseRequest;
import com.wander.expense.dto.ExpenseShareInput;
import com.wander.expense.dto.ExpenseSummary;
import com.wander.expense.dto.ExpenseView;
import com.wander.expense.dto.PaymentRequest;
import com.wander.expense.dto.PersonBalance;
import com.wander.expense.dto.SettlementView;
import com.wander.expense.dto.TripExpenses;
import com.wander.fx.CurrencyConversion;
import com.wander.fx.FxService;
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
 *
 * A foreign currency changes where that arithmetic happens and nothing about the
 * invariant. The amount and the shares arrive in whatever was on the bill, the
 * **total is converted once** into the trip's currency, and the split is made of
 * the converted total — never of converted shares, which would round
 * independently and leave a cent beside an expense whose own total was right.
 * See {@code ExpenseSplitter.proportionalShares}. Everything downstream of this
 * class goes on seeing one currency, which is what lets balances stay addition.
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
    private final FxService fx;

    public ExpenseService(ExpenseRepository expenses, TripMemberRepository members, UserRepository users,
            TripAccessService access, TripChanges changes, FxService fx) {
        this.expenses = expenses;
        this.members = members;
        this.users = users;
        this.access = access;
        this.changes = changes;
        this.fx = fx;
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

        // Converted before the expense exists, because the converted figure is
        // what `amountMinor` has always meant and there is no moment at which
        // this row holds yen in a column the totals read as euros.
        Converted converted = convert(trip, null, request.currency(), request.fxRate(),
                request.amountMinor(), request.spentOn());

        Expense expense = new Expense(trip, request.description().strip(), converted.tripAmountMinor(),
                request.spentOn(), requireMemberUser(tripId, request.paidByUserId(), "paidByUserId"),
                request.splitMode(), ExpenseKind.EXPENSE);
        converted.applyTo(expense);
        applySplit(tripId, expense, request, converted);
        Expense saved = expenses.save(expense);

        changes.expensesChanged(tripId, userId);
        return ExpenseView.of(saved);
    }

    @Transactional
    public ExpenseView update(Long userId, Long tripId, Long expenseId, ExpenseRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Expense expense = require(tripId, expenseId);
        if (expense.isPayment()) {
            // A payment's shape is derived, not given: rewriting it through this
            // body could put its single share on the wrong person and reverse a
            // balance. Remove it and record it again instead.
            throw new ConflictException("A payment is recorded or removed, not edited");
        }

        // The existing row is handed over so its rate can be reused: an edit
        // re-runs the arithmetic but must not re-run the lookup. See `convert`.
        Converted converted = convert(expense.getTrip(), expense, request.currency(), request.fxRate(),
                request.amountMinor(), request.spentOn());

        expense.setDescription(request.description().strip());
        expense.setAmountMinor(converted.tripAmountMinor());
        expense.setSpentOn(request.spentOn());
        expense.setPaidBy(requireMemberUser(tripId, request.paidByUserId(), "paidByUserId"));
        expense.setSplitMode(request.splitMode());
        converted.applyTo(expense);
        // Written whole: changing who was in the split changes everybody's share,
        // so there is nothing sensible to patch.
        applySplit(tripId, expense, request, converted);

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
     * Records that somebody settled up.
     *
     * Stored as an expense whose payer is the person handing money over and whose
     * single share belongs to the person receiving it. Nothing else in this class
     * changes, because that is exactly what makes the balances come out right:
     * `paid` rises for the payer and `owed` rises for the recipient, so a -40 and
     * a +40 both become zero.
     */
    @Transactional
    public ExpenseView recordPayment(Long userId, Long tripId, PaymentRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        if (request.fromUserId().equals(request.toUserId())) {
            throw new IllegalArgumentException("A payment needs two different people");
        }

        User from = requireMemberUser(tripId, request.fromUserId(), "fromUserId");
        User to = requireMemberUser(tripId, request.toUserId(), "toUserId");
        String note = request.note() == null || request.note().isBlank()
                ? "Payment"
                : request.note().strip();

        // Cash handed over abroad is handed over in the cash somebody has, so a
        // payment converts exactly as an expense does.
        Converted converted = convert(member.getTrip(), null, request.currency(), request.fxRate(),
                request.amountMinor(), request.paidOn());

        // EXACT, because the amount is given rather than divided — and the whole
        // amount goes to one person, so there is nothing for the splitter to do.
        Expense payment = new Expense(member.getTrip(), note, converted.tripAmountMinor(), request.paidOn(),
                from, SplitMode.EXACT, ExpenseKind.PAYMENT);
        converted.applyTo(payment);
        // The share is the converted figure: a payment's single share is what
        // clears a balance, and balances are kept in the trip's currency.
        payment.replaceShares(Map.of(to.getId(), converted.tripAmountMinor()), ignored -> to);
        Expense saved = expenses.save(payment);

        changes.expensesChanged(tripId, userId);
        return ExpenseView.of(saved);
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
    private void applySplit(Long tripId, Expense expense, ExpenseRequest request, Converted converted) {
        List<Long> participants = request.shares().stream().map(ExpenseShareInput::userId).distinct().toList();
        if (participants.size() != request.shares().size()) {
            throw new IllegalArgumentException("A participant appears twice in the split");
        }
        Map<Long, User> participantUsers = participants.stream()
                .collect(Collectors.toMap(id -> id, id -> requireMemberUser(tripId, id, "shares[].userId")));

        Map<Long, Long> amounts;
        if (request.splitMode() == SplitMode.EQUAL) {
            // Divided *after* the conversion, never before: dividing first and
            // converting each part would round every share on its own and leave
            // the pieces adding up to something other than the whole.
            amounts = ExpenseSplitter.equalShares(converted.tripAmountMinor(), participants);
        } else {
            Map<Long, Long> typed = new LinkedHashMap<>();
            long total = 0;
            for (ExpenseShareInput share : request.shares()) {
                if (share.amountMinor() == null) {
                    throw new IllegalArgumentException("An exact split needs an amount for every participant");
                }
                typed.put(share.userId(), share.amountMinor());
                total += share.amountMinor();
            }
            // Checked in the currency they were typed in, which is the currency
            // the bill was in — telling somebody their yen do not add up to a
            // euro total would be arithmetic nobody could follow.
            if (total != request.amountMinor()) {
                throw new IllegalArgumentException(
                        "The shares add up to " + total + " but the expense is " + request.amountMinor());
            }
            // For an expense in the trip's own currency this is the identity:
            // the total being divided is the sum of the weights, so everybody
            // gets back exactly what they typed. One code path, no special case.
            amounts = ExpenseSplitter.proportionalShares(converted.tripAmountMinor(), typed);
        }
        expense.replaceShares(amounts, userId -> participantUsers.get(userId));
    }

    /**
     * Works out what this expense is worth in the trip's currency, and how that
     * was arrived at.
     *
     * Four paths, in the order they are tried, and the order is the design:
     *
     * <ol>
     * <li>The currency is the trip's — nothing to convert, and no upstream is
     * troubled. This is the overwhelmingly common case and it must stay free.</li>
     * <li>A rate was supplied. It replaces the lookup rather than overriding its
     * answer, so this works with no outbound network at all — and it is the more
     * accurate number for anybody reading it off a card statement, which knows
     * what was really charged where a reference rate does not.</li>
     * <li>The row already has a rate for this currency and this date. **Reused,
     * not refetched**, which is what makes "frozen at entry" true: renaming an
     * expense re-runs this method, and a fresh lookup would silently move a
     * balance. A rate somebody typed survives here too — it was a deliberate
     * act, and an unrelated edit must not quietly replace it with a market
     * one.</li>
     * <li>Otherwise it is looked up for the day it was spent. Failure stops the
     * write; see {@code FxService} for why this is the one upstream in the
     * project that is not allowed to fail quietly.</li>
     * </ol>
     *
     * A change of currency or of date falls through to the lookup on purpose:
     * both make the row a different claim about what happened, and carrying the
     * old rate across would attach Tuesday's yen rate to a Friday in dollars.
     */
    private Converted convert(Trip trip, Expense existing, String requestedCurrency, String requestedRate,
            long amountMinor, LocalDate spentOn) {
        String tripCurrency = CurrencyConversion.normalise(trip.getCurrency());
        String currency = requestedCurrency == null || requestedCurrency.isBlank()
                ? tripCurrency
                : CurrencyConversion.normalise(requestedCurrency);

        if (currency.equals(tripCurrency)) {
            return Converted.plain(amountMinor);
        }
        if (requestedRate != null && !requestedRate.isBlank()) {
            return Converted.of(fx.convertAt(amountMinor, currency, tripCurrency, parseRate(requestedRate)));
        }
        if (existing != null && existing.isConverted()
                && currency.equals(existing.getSourceCurrency())
                && spentOn.equals(existing.getSpentOn())) {
            return Converted.of(fx.reapply(amountMinor, currency, tripCurrency, existing.getFxRate(),
                    existing.getFxQuotedOn(), existing.isFxManual()));
        }
        return Converted.of(fx.convert(amountMinor, currency, tripCurrency, spentOn));
    }

    /**
     * A rate off the wire, as text.
     *
     * Parsed here rather than bound as a number by Jackson, which is the same
     * rule the amounts follow: a JSON number is a binary float on the way in, and
     * this feature does not let one near the arithmetic. A blank or malformed
     * one is a 400 naming the field, because it is a value somebody typed into a
     * box and the answer is to retype it.
     */
    private static BigDecimal parseRate(String rate) {
        try {
            BigDecimal parsed = new BigDecimal(rate.strip());
            if (parsed.signum() <= 0) {
                throw new IllegalArgumentException("fxRate: an exchange rate has to be more than zero");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("fxRate: '" + rate + "' is not a number");
        }
    }

    /**
     * The outcome of that: what the expense is worth in the trip's currency, and
     * the receipt to store beside it.
     *
     * {@code sourceCurrency} being null is what "paid in the trip's own currency"
     * means throughout this feature, in the request, in this record and in the
     * table.
     */
    private record Converted(long tripAmountMinor, String sourceCurrency, long sourceAmountMinor,
            BigDecimal rate, LocalDate quotedOn, boolean manual) {

        static Converted plain(long amountMinor) {
            return new Converted(amountMinor, null, amountMinor, null, null, false);
        }

        static Converted of(FxService.Conversion conversion) {
            return new Converted(conversion.targetMinor(), conversion.sourceCurrency(),
                    conversion.sourceMinor(), conversion.rate(), conversion.quotedOn(),
                    conversion.manual());
        }

        /**
         * Writes the receipt onto the expense — or clears it, which is the half
         * that is easy to forget: an expense edited back into the trip's own
         * currency has to lose its rate, or the row would go on claiming a
         * conversion that no longer happened.
         */
        void applyTo(Expense expense) {
            if (sourceCurrency == null) {
                expense.clearConversion();
            } else {
                expense.setConversion(sourceCurrency, sourceAmountMinor, rate, quotedOn, manual);
            }
        }
    }

    /**
     * Everybody's standing, plus the fewest payments that would clear it.
     *
     * Computed here rather than in the client: there should be exactly one
     * implementation of this arithmetic, and it should be the one with tests
     * around it.
     */
    private ExpenseSummary summarise(Long tripId, List<Expense> all) {
        // Expenses and payments are accumulated apart. The net is the same either
        // way, but the four figures are what a person checks their own memory
        // against, and "your share" has to mean your share of the bills.
        Map<Long, Long> paid = new LinkedHashMap<>();
        Map<Long, Long> owed = new LinkedHashMap<>();
        Map<Long, Long> paidBack = new LinkedHashMap<>();
        Map<Long, Long> received = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        long total = 0;

        for (Expense expense : all) {
            boolean payment = expense.isPayment();
            // A payment moves money between members; it is not something the trip
            // spent. It still moves the net, which is how settling up clears a
            // balance at all.
            if (!payment) {
                total += expense.getAmountMinor();
            }
            (payment ? paidBack : paid)
                    .merge(expense.getPaidBy().getId(), expense.getAmountMinor(), Long::sum);
            names.putIfAbsent(expense.getPaidBy().getId(), expense.getPaidBy().getDisplayName());
            for (ExpenseShare share : expense.getShares()) {
                (payment ? received : owed)
                        .merge(share.getUser().getId(), share.getAmountMinor(), Long::sum);
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
            boolean involved = paid.containsKey(id) || owed.containsKey(id)
                    || paidBack.containsKey(id) || received.containsKey(id);
            if (currentMembers.contains(id) || involved) {
                nets.put(id, paid.getOrDefault(id, 0L) + paidBack.getOrDefault(id, 0L)
                        - owed.getOrDefault(id, 0L) - received.getOrDefault(id, 0L));
            }
        }

        List<PersonBalance> balances = nets.entrySet().stream()
                .map(entry -> new PersonBalance(entry.getKey(), names.get(entry.getKey()),
                        paid.getOrDefault(entry.getKey(), 0L), owed.getOrDefault(entry.getKey(), 0L),
                        paidBack.getOrDefault(entry.getKey(), 0L),
                        received.getOrDefault(entry.getKey(), 0L),
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
