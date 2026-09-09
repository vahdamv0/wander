package com.wander.demo;

import com.wander.auth.UserAccountService;
import com.wander.config.WanderProperties;
import com.wander.day.DayNote;
import com.wander.day.DayNoteRepository;
import com.wander.demo.DemoContent.DemoBooking;
import com.wander.demo.DemoContent.DemoExpense;
import com.wander.demo.DemoContent.DemoPacking;
import com.wander.demo.DemoContent.DemoPlace;
import com.wander.expense.*;
import com.wander.fx.CurrencyConversion;
import com.wander.packing.PackingItem;
import com.wander.packing.PackingItemRepository;
import com.wander.place.Place;
import com.wander.place.PlaceRepository;
import com.wander.reservation.Reservation;
import com.wander.reservation.ReservationKind;
import com.wander.reservation.ReservationRepository;
import com.wander.trip.*;
import com.wander.user.GlobalRole;
import com.wander.user.User;
import com.wander.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A worked example trip, and a read-only account to look at it with.
 *
 * A public instance with nothing on it demonstrates nothing, and the parts of
 * wander worth showing — a split that does not sum to a round number, a flight
 * that lands in another timezone, a day that cost nothing — only exist once
 * there is data with those shapes in it.
 *
 * **Off by default.** This writes rows, so it is opt-in per instance
 * ({@code WANDER_DEMO_ENABLED}) and belongs on a demo box rather than on
 * somebody's real one.
 *
 * Four decisions are worth knowing:
 *
 *  - **The visitor is a VIEWER, not the owner.** The content belongs to two
 *    accounts nobody can sign in as (their passwords are random and never
 *    printed), and the published account is added to the trip as a viewer. So
 *    read-only is enforced by {@code TripAccessService} — the same 403 anybody
 *    else would get — rather than by hiding buttons, and the READ ONLY badge in
 *    the interface is part of the demonstration rather than an apology.
 *  - **It re-seeds on every boot**, and only ever within the demo owner's own
 *    trips. That is what keeps the dates fresh: a forecast exists for about
 *    sixteen days, so a trip with fixed dates would quietly lose the best half of
 *    its day cards and then become a trip in the past. Restarting the container
 *    is also how a demo instance gets tidied up after visitors.
 *  - **The accounts are created once and never rewritten.** Re-seeding replaces
 *    the trip, not the people — otherwise the published password would change
 *    under whoever was reading the login page.
 *  - **It writes through the entities, not the services.** A service call would
 *    publish {@code TripChanges} events to sockets that cannot exist yet, and
 *    would check permissions on behalf of a caller that is not a request.
 *
 * It is an {@code ApplicationRunner} itself rather than a {@code @Bean} method
 * returning a lambda, and that is not a style preference: {@code @Transactional}
 * is applied by a proxy, and a lambda inside this class calling its own method
 * is self-invocation, which goes straight past it. Spring calls {@code run} on
 * the proxy, so the whole seed is one transaction and a failure half way through
 * leaves no half-built trip behind.
 */
@Component
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The people the trip belongs to. Nobody signs in as either. */
    private static final String OWNER_EMAIL = "demo-owner@wander.local";
    private static final String OWNER_NAME = "Sam Alder";
    private static final String FRIEND_EMAIL = "demo-friend@wander.local";
    private static final String FRIEND_NAME = "Mira Okafor";

    private final WanderProperties properties;
    private final UserAccountService accounts;
    private final UserRepository users;
    private final TripRepository trips;
    private final TripMemberRepository members;
    private final PlaceRepository places;
    private final DayNoteRepository notes;
    private final ExpenseRepository expenses;
    private final PackingItemRepository packing;
    private final ReservationRepository reservations;

    public DemoSeeder(WanderProperties properties, UserAccountService accounts, UserRepository users,
            TripRepository trips, TripMemberRepository members, PlaceRepository places,
            DayNoteRepository notes, ExpenseRepository expenses, PackingItemRepository packing,
            ReservationRepository reservations) {
        this.properties = properties;
        this.accounts = accounts;
        this.users = users;
        this.trips = trips;
        this.members = members;
        this.places = places;
        this.notes = notes;
        this.expenses = expenses;
        this.packing = packing;
        this.reservations = reservations;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.demo().enabled()) {
            return;
        }

        User owner = accountFor(OWNER_EMAIL, OWNER_NAME);
        User friend = accountFor(FRIEND_EMAIL, FRIEND_NAME);
        User visitor = accountFor(properties.demo().email(), "Demo visitor",
                properties.demo().password());

        // Only ever the demo owner's own trips. Scoping the delete this way is
        // what makes re-seeding safe to run against a database that also holds
        // real ones — switching the flag on by mistake costs nothing.
        List<Trip> existing = trips.findAllForUser(owner.getId());
        if (!existing.isEmpty()) {
            trips.deleteAll(existing);
            trips.flush();
        }

        LocalDate start = LocalDate.now().plusDays(DemoContent.STARTS_IN_DAYS);
        LocalDate end = start.plusDays(DemoContent.LENGTH_DAYS - 1L);
        Trip trip = trips.save(new Trip(DemoContent.TRIP_NAME, DemoContent.TRIP_DESTINATION,
                start, end, DemoContent.TRIP_CURRENCY));

        members.save(new TripMember(trip, owner, TripRole.OWNER));
        members.save(new TripMember(trip, friend, TripRole.EDITOR));
        members.save(new TripMember(trip, visitor, TripRole.VIEWER));

        addPlaces(trip, start);
        addNotes(trip, start);
        addMoney(trip, start, List.of(owner, friend));
        addPacking(trip, List.of(owner, friend));
        addBookings(trip, start);

        log.info("Demo trip seeded: '{}' {} to {}, readable by {}",
                trip.getName(), start, end, properties.demo().email());
    }

    private void addPlaces(Trip trip, LocalDate start) {
        Map<Integer, Integer> nextOrder = new HashMap<>();
        for (DemoPlace spec : DemoContent.PLACES) {
            int order = nextOrder.merge(spec.day(), 1, Integer::sum) - 1;
            Place place = new Place(trip, start.plusDays(spec.day()), order, spec.name());
            place.setLocation(spec.lat(), spec.lon(), spec.address());
            place.setOsmRef(spec.osmRef());
            place.setCategory(spec.category());
            place.setStartsAt(spec.at());
            if (spec.note() != null) {
                place.replaceNotes(List.of(spec.note()));
            }
            // setPhoto refuses a URL without its credit, which is the rule that
            // makes these constants safe to ship: an unattributable picture
            // cannot be seeded even by accident.
            if (spec.photoUrl() != null) {
                place.setPhoto(spec.photoUrl(), spec.photoThumbUrl(), spec.photoAuthor(),
                        spec.photoLicence(), spec.photoSourceUrl());
            }
            places.save(place);
        }
    }

    private void addNotes(Trip trip, LocalDate start) {
        DemoContent.NOTES.forEach(spec ->
                notes.save(new DayNote(trip, start.plusDays(spec.day()), spec.note())));
    }

    private void addMoney(Trip trip, LocalDate start, List<User> travellers) {
        List<Long> ids = travellers.stream().map(User::getId).toList();
        for (DemoExpense spec : DemoContent.EXPENSES) {
            BigDecimal rate = spec.fxRate() == null
                    ? null
                    : new BigDecimal(spec.fxRate())
                            .setScale(CurrencyConversion.RATE_SCALE, RoundingMode.HALF_UP);
            long amountMinor = rate == null
                    ? spec.amountMinor()
                    : CurrencyConversion.convert(spec.amountMinor(), spec.sourceCurrency(),
                            DemoContent.TRIP_CURRENCY, rate);

            Expense expense = new Expense(trip, spec.description(), amountMinor,
                    start.plusDays(spec.dayOffset()), travellers.get(spec.paidBy()),
                    spec.exact() ? SplitMode.EXACT : SplitMode.EQUAL, ExpenseKind.EXPENSE);
            if (rate != null) {
                expense.setConversion(spec.sourceCurrency(), spec.amountMinor(), rate, null, true);
            }

            Map<Long, Long> shares = new HashMap<>();
            if (spec.exact()) {
                Map<Long, Long> typed = new HashMap<>();
                for (int i = 0; i < ids.size(); i++) {
                    typed.put(ids.get(i), spec.shares()[i]);
                }
                shares.putAll(ExpenseSplitter.proportionalShares(amountMinor, typed));
            } else {
                // The same splitter the endpoint uses, so the remainder lands
                // where it really lands rather than where a seeder guessed.
                shares.putAll(ExpenseSplitter.equalShares(amountMinor, ids));
            }
            expense.replaceShares(shares, id -> travellers.stream()
                    .filter(u -> u.getId().equals(id)).findFirst().orElseThrow());
            expenses.save(expense);
        }

        // A payment is an expense with a kind, so `net = paid - owed` still
        // clears the balance and the trip's total stays what the trip cost.
        Expense payment = new Expense(trip, "Settling up mid-trip", DemoContent.PAYMENT_MINOR,
                start.plusDays(DemoContent.PAYMENT_DAY), travellers.get(1),
                SplitMode.EXACT, ExpenseKind.PAYMENT);
        payment.replaceShares(Map.of(ids.get(0), DemoContent.PAYMENT_MINOR),
                id -> travellers.get(0));
        expenses.save(payment);
    }

    private void addPacking(Trip trip, List<User> travellers) {
        for (DemoPacking spec : DemoContent.PACKING) {
            User assignee = spec.assignee() == null ? null : travellers.get(spec.assignee());
            PackingItem item = new PackingItem(trip, spec.description(), assignee);
            if (spec.packed()) {
                item.setPacked(true, assignee == null ? travellers.get(0) : assignee);
            }
            packing.save(item);
        }
    }

    private void addBookings(Trip trip, LocalDate start) {
        for (DemoBooking spec : DemoContent.BOOKINGS) {
            Reservation booking = new Reservation(trip,
                    ReservationKind.valueOf(spec.kind()), spec.title());
            booking.setWhen(instant(start, spec.startDay(), spec.startTime(), spec.startZone()),
                    spec.startZone(),
                    spec.endDay() == null ? null
                            : instant(start, spec.endDay(), spec.endTime(), spec.endZone()),
                    spec.endZone());
            booking.setConfirmation(spec.confirmation());
            booking.setPhone(spec.phone());
            booking.setNotes(spec.notes());
            reservations.save(booking);
        }
    }

    /** Wall clock plus zone to an instant, exactly as the endpoint does it. */
    private static Instant instant(LocalDate start, int dayOffset, String time, String zone) {
        return LocalDateTime.of(start.plusDays(dayOffset), LocalTime.parse(time))
                .atZone(ZoneId.of(zone)).toInstant();
    }

    private User accountFor(String email, String displayName) {
        return accountFor(email, displayName, null);
    }

    /**
     * The account, made once. A password of null means one nobody is meant to
     * have: it is generated, never printed, and never needed — the trip is
     * reachable through the viewer account instead.
     */
    private User accountFor(String email, String displayName, String password) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> accounts.create(email,
                displayName, password == null ? unusablePassword() : password, GlobalRole.USER));
    }

    private static String unusablePassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
