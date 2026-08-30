package com.wander.admin;

import java.time.Instant;

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
 * A one-shot permission to set one account's password without knowing the old
 * one.
 *
 * The entity never sees the token, only its digest — {@link AdminService} mints
 * it, returns it once and keeps the hash, so there is no accessor here that
 * could hand a working link to a later reader of the database. Same bargain as
 * {@code TripInvite}, with a sharper edge: this one sets a password.
 *
 * Status is derived rather than stored, for the reason that applies everywhere
 * else in this project — a column saying "expired" would need somebody to write
 * it at the right moment, and nothing here runs on a clock.
 */
@Entity
@Table(name = "password_resets")
public class PasswordReset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PasswordReset() {
        // JPA
    }

    public PasswordReset(User user, String tokenHash, User createdBy, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    /**
     * Whether this link would still set a password.
     *
     * One method rather than three checks at each call site: it is asked when the
     * link is previewed and again when it is redeemed, and those two answers
     * disagreeing is exactly how a revoked link gets honoured.
     */
    public boolean isUsable(Instant now) {
        return usedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    public ResetStatus statusAt(Instant now) {
        if (revokedAt != null) {
            return ResetStatus.REVOKED;
        }
        if (usedAt != null) {
            return ResetStatus.USED;
        }
        return now.isBefore(expiresAt) ? ResetStatus.PENDING : ResetStatus.EXPIRED;
    }

    public void used(Instant when) {
        this.usedAt = when;
    }

    public void revoke(Instant when) {
        this.revokedAt = when;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
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

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
