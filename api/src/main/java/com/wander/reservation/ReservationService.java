package com.wander.reservation;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;
import com.wander.reservation.dto.ReservationRequest;
import com.wander.reservation.dto.ReservationView;
import com.wander.reservation.dto.TripReservations;
import com.wander.sync.TripChanges;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripMember;
import com.wander.trip.TripRole;
import com.wander.trip.dto.TripSummary;

/**
 * Bookings, in time order.
 *
 * The only thing here that is not the usual shape is the clock. A wall-clock time
 * and a zone arrive; an instant is stored. That conversion is the service's job
 * rather than the client's, so there is one implementation of it and it is the
 * one with a tz database behind it.
 */
@Service
public class ReservationService {

    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    private final ReservationRepository reservations;
    private final TripAccessService access;
    private final TripChanges changes;

    public ReservationService(ReservationRepository reservations, TripAccessService access,
            TripChanges changes) {
        this.reservations = reservations;
        this.access = access;
        this.changes = changes;
    }

    @Transactional(readOnly = true)
    public TripReservations list(Long userId, Long tripId) {
        TripMember member = access.requireMember(tripId, userId);
        List<ReservationView> all = reservations.findByTripIdOrderByStartsAtAscIdAsc(tripId).stream()
                .map(ReservationView::of)
                .toList();
        return new TripReservations(TripSummary.of(member.getTrip(), member.getRole()), all);
    }

    @Transactional
    public ReservationView create(Long userId, Long tripId, ReservationRequest request) {
        TripMember member = access.requireRole(tripId, userId, CAN_EDIT);
        Reservation reservation = new Reservation(member.getTrip(), request.kind(),
                request.title().strip());
        apply(reservation, request);
        ReservationView view = ReservationView.of(reservations.save(reservation));
        changes.reservationsChanged(tripId, userId);
        return view;
    }

    @Transactional
    public ReservationView update(Long userId, Long tripId, Long reservationId,
            ReservationRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Reservation reservation = require(tripId, reservationId);
        reservation.setKind(request.kind());
        reservation.setTitle(request.title().strip());
        apply(reservation, request);
        changes.reservationsChanged(tripId, userId);
        return ReservationView.of(reservation);
    }

    @Transactional
    public void delete(Long userId, Long tripId, Long reservationId) {
        access.requireRole(tripId, userId, CAN_EDIT);
        reservations.delete(require(tripId, reservationId));
        changes.reservationsChanged(tripId, userId);
    }

    private void apply(Reservation reservation, ReservationRequest request) {
        reservation.setConfirmation(blankToNull(request.confirmation()));
        reservation.setPhone(blankToNull(request.phone()));
        reservation.setNotes(blankToNull(request.notes()));

        ZoneId startZone = zone(request.startZone(), "startZone");
        // The end's zone defaults to the start's: everything that is not a flight
        // ends where it began, and repeating the zone would be a field to get
        // wrong for no gain.
        String endZoneId = blankToNull(request.endZone()) == null
                ? request.startZone()
                : request.endZone();
        ZoneId endZone = request.endsAtLocal() == null ? startZone : zone(endZoneId, "endZone");

        reservation.setWhen(toInstant(request.startsAtLocal(), startZone), startZone.getId(),
                request.endsAtLocal() == null ? null : toInstant(request.endsAtLocal(), endZone),
                request.endsAtLocal() == null ? null : endZone.getId());
    }

    /**
     * A wall-clock time in a zone, as an instant.
     *
     * `atZone` is deliberate about the two awkward hours of the year: a time that
     * does not exist (the spring-forward gap) is pushed forward rather than
     * refused, and a time that happens twice (the autumn overlap) takes the
     * earlier offset. Both are Java's documented defaults, and both beat refusing
     * a booking somebody genuinely holds.
     */
    private static Instant toInstant(LocalDateTime local, ZoneId zone) {
        return local.atZone(zone).toInstant();
    }

    /**
     * An unknown zone is a 400, not a 500. It is the one field here a client can
     * get wrong in a way that makes the whole record undisplayable, so it is
     * checked against the tz database rather than trusted.
     */
    private static ZoneId zone(String id, String field) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(field + ": " + id + " is not a known time zone");
        }
    }

    private Reservation require(Long tripId, Long reservationId) {
        return reservations.findByIdAndTripId(reservationId, tripId)
                .orElseThrow(() -> new NotFoundException("Reservation " + reservationId + " not found"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
