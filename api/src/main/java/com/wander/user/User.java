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
}
