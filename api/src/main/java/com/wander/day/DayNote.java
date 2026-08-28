package com.wander.day;

import java.time.Instant;
import java.time.LocalDate;

import com.wander.trip.Trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One note on one day of a trip. There is at most one per day — the unique
 * constraint on (trip_id, day_date) is the real key; the surrogate id is only
 * so the row looks like every other row here.
 */
@Entity
@Table(name = "day_notes")
public class DayNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /** Derived days have no rows, so the day is a date. Same shape as Place. */
    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    /** Never blank: clearing a note deletes the row. */
    @Column(nullable = false, length = 4000)
    private String note;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DayNote() {
        // JPA
    }

    public DayNote(Trip trip, LocalDate dayDate, String note) {
        this.trip = trip;
        this.dayDate = dayDate;
        setNote(note);
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public LocalDate getDayDate() {
        return dayDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
