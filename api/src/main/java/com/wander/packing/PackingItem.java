package com.wander.packing;

import java.time.Instant;

import com.wander.trip.Trip;
import com.wander.user.User;

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
 * One thing to bring.
 *
 * `assignee` is nullable and that null is meaningful: it is the shared pile, not
 * a missing value. Everything else in this project belongs to the trip outright;
 * this is the first thing that can belong to one person on it.
 */
@Entity
@Table(name = "packing_items")
public class PackingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(nullable = false)
    private String description;

    /** Null means everyone's. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    private User assignee;

    @Column(nullable = false)
    private boolean packed;

    /**
     * Who ticked it, which is the whole point of recording it for a shared item:
     * "packed" on the tent is only useful if somebody knows whose bag it is in.
     * Cleared when the item is unticked, so it never describes a past state.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "packed_by_user_id")
    private User packedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PackingItem() {
        // JPA
    }

    public PackingItem(Trip trip, String description, User assignee) {
        this.trip = trip;
        this.description = description;
        this.assignee = assignee;
        this.packed = false;
        this.createdAt = Instant.now();
    }

    /** Ticking and unticking, with the "by whom" kept in step. */
    public void setPacked(boolean packed, User by) {
        this.packed = packed;
        this.packedBy = packed ? by : null;
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

    public User getAssignee() {
        return assignee;
    }

    public void setAssignee(User assignee) {
        this.assignee = assignee;
    }

    public boolean isPacked() {
        return packed;
    }

    public User getPackedBy() {
        return packedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
