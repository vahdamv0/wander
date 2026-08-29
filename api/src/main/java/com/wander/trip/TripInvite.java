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

/**
 * A link that lets one person join one trip, once, before a deadline.
 *
 * The entity never sees the token itself — only its hash. {@link TripInviteService}
 * mints the token, hands it to the caller once, and keeps nothing but the digest,
 * so there is no accessor here that could return a working invitation to a later
 * reader of the database. See {@code V15__trip_invites.sql} for why that matters
 * more here than anywhere else in the schema.
 *
 * Four states, and they are derived rather than stored: a column that says
 * "expired" would need somebody to write it at the right moment, and nothing runs
 * at midnight in this application.
 */
@Entity
@Table(name = "trip_invites")
public class TripInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TripRole role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by_user_id")
    private User acceptedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected TripInvite() {
        // JPA
    }

    public TripInvite(Trip trip, String tokenHash, TripRole role, User createdBy, Instant expiresAt) {
        this.trip = trip;
        this.tokenHash = tokenHash;
        this.role = role;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    /**
     * Whether this link would still let somebody in.
     *
     * One method rather than three checks at each call site: "is it usable" is
     * asked when previewing a link and again when accepting it, and the two
     * answers disagreeing is exactly how a revoked invitation gets honoured.
     */
    public boolean isUsable(Instant now) {
        return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    public InviteStatus statusAt(Instant now) {
        if (revokedAt != null) {
            return InviteStatus.REVOKED;
        }
        if (acceptedAt != null) {
            return InviteStatus.ACCEPTED;
        }
        return now.isBefore(expiresAt) ? InviteStatus.PENDING : InviteStatus.EXPIRED;
    }

    /** Marks the link used. Single use is what stops a forwarded link adding a second stranger. */
    public void acceptedBy(User user, Instant when) {
        this.acceptedBy = user;
        this.acceptedAt = when;
    }

    public void revoke(Instant when) {
        this.revokedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public TripRole getRole() {
        return role;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public User getAcceptedBy() {
        return acceptedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
