package com.wander.reservation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.wander.trip.Trip;

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
import jakarta.persistence.Table;

/**
 * One booking.
 *
 * Time is an {@link Instant} plus the zone it was booked in, and both are
 * load-bearing. The instant answers "which of these is first", which is the only
 * ordering that is correct when a trip crosses zones. The zone is what turns it
 * back into "09:15", because an instant on its own cannot say that — and 09:15 is
 * what is printed on the ticket and what people say to each other.
 *
 * A flight has two of them: it leaves one place and lands in another.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationKind kind;

    @Column(nullable = false)
    private String title;

    @Column
    private String confirmation;

    @Column
    private String notes;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "start_zone", nullable = false, length = 64)
    private String startZone;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "end_zone", length = 64)
    private String endZone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Reservation() {
        // JPA
    }

    public Reservation(Trip trip, ReservationKind kind, String title) {
        this.trip = trip;
        this.kind = kind;
        this.title = title;
        this.createdAt = Instant.now();
    }

    /**
     * Sets both ends at once, because they constrain each other: an end before a
     * start is nonsense, and an end with no zone to read it in is undisplayable.
     * The database says the same thing in two CHECK constraints; this is what
     * turns a violation into a 400 rather than a 500.
     */
    public void setWhen(Instant startsAt, String startZone, Instant endsAt, String endZone) {
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("A booking cannot end before it starts");
        }
        this.startsAt = startsAt;
        this.startZone = startZone;
        this.endsAt = endsAt;
        // An end in the same place as the start is the common case, so its zone
        // follows rather than having to be repeated.
        this.endZone = endsAt == null ? null : (endZone == null ? startZone : endZone);
    }

    /** The wall clock it was booked at: what the ticket says. */
    public LocalDateTime localStart() {
        return LocalDateTime.ofInstant(startsAt, ZoneId.of(startZone));
    }

    public LocalDateTime localEnd() {
        return endsAt == null ? null : LocalDateTime.ofInstant(endsAt, ZoneId.of(endZone));
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public ReservationKind getKind() {
        return kind;
    }

    public void setKind(ReservationKind kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getConfirmation() {
        return confirmation;
    }

    public void setConfirmation(String confirmation) {
        this.confirmation = confirmation;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public String getStartZone() {
        return startZone;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public String getEndZone() {
        return endZone;
    }
}
