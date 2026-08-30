package com.wander.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stored lower-cased; the unique index is on the lower-cased value too. */
    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /**
     * Always a DelegatingPasswordEncoder value, i.e. prefixed with its algorithm
     * ({bcrypt}...). That prefix is what lets a future move to Argon2 re-hash
     * users on their next login instead of needing a migration.
     */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GlobalRole role = GlobalRole.USER;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * When this account was taken out of service, or null for one in normal use.
     *
     * A timestamp rather than a boolean, because "when did this happen" is the
     * first question asked about an account that has been shut off, and the row
     * is no bigger for answering it — the same choice
     * {@code trip_invites.revoked_at} makes.
     *
     * Disabling is deliberately the only way an account stops being usable;
     * there is no delete. Expenses and packing items reference their user, and a
     * departed member's shares are history the trip still needs, so removing the
     * row would silently forgive a debt on somebody else's trip.
     */
    @Column(name = "disabled_at")
    private Instant disabledAt;

    protected User() {
        // JPA
    }

    public User(String email, String displayName, String passwordHash, GlobalRole role) {
        this.email = email.toLowerCase();
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public GlobalRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public boolean isDisabled() {
        return disabledAt != null;
    }

    /**
     * Takes the account out of service.
     *
     * A second call leaves the original timestamp alone: when it was *first*
     * disabled is the fact worth keeping, and an admin clicking twice should not
     * quietly rewrite it.
     */
    public void disable(Instant when) {
        if (disabledAt == null) {
            this.disabledAt = when;
        }
    }

    public void enable() {
        this.disabledAt = null;
    }

    /**
     * Replace the stored hash. Takes an already-encoded value rather than a raw
     * password on purpose: the encoder is a Spring bean and this is an entity,
     * so a method taking plaintext here would either need one injected or would
     * be an invitation to store one by mistake. Encoding stays in
     * {@code UserAccountService}, which is also where the current password is
     * verified.
     */
    public void changePassword(String encodedPasswordHash) {
        this.passwordHash = encodedPasswordHash;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }
}
