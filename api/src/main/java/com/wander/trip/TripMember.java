package com.wander.trip;

import java.time.Instant;

import com.wander.user.User;

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
import jakarta.persistence.UniqueConstraint;

/**
 * Membership is the only thing that grants access to a trip — there is no owner
 * column on `trips`. One place to ask "may this user see this trip", so a new
 * feature cannot accidentally consult a staler copy of the answer.
 */
@Entity
@Table(name = "trip_members", uniqueConstraints = @UniqueConstraint(columnNames = {"trip_id", "user_id"}))
public class TripMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    protected TripMember() {
        // JPA
    }

    public TripMember(Trip trip, User user, TripRole role) {
        this.trip = trip;
        this.user = user;
        this.role = role;
        this.joinedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public User getUser() {
        return user;
    }

    public TripRole getRole() {
        return role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
