package com.wander.trip;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String destination;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /**
     * ISO 4217, and fixed at creation. One currency per trip is what keeps a
     * balance integer addition rather than a question about exchange rates; and
     * changing it later could not be a relabel, because the amounts already
     * stored mean something in the old one.
     */
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Trip() {
        // JPA
    }

    public Trip(String name, String destination, LocalDate startDate, LocalDate endDate, String currency) {
        this.name = name;
        this.destination = destination;
        this.startDate = startDate;
        this.endDate = endDate;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    /**
     * Days are derived from the date range, never stored. That is deliberate: a
     * stored day list is a second copy of the same fact, and moving a trip's
     * dates then means keeping two things in step.
     */
    public int dayCount() {
        return (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /**
     * Anything hung off a day — a place, a note — has to fall inside the range,
     * or it would belong to a day the client never draws and simply disappear.
     * The check lives here because more than one service needs it and the rule
     * is the trip's, not theirs.
     */
    public void requireCovers(LocalDate day) {
        if (day.isBefore(startDate) || day.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "dayDate must fall between " + startDate + " and " + endDate);
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDestination() {
        return destination;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
