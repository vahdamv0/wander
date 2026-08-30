package com.wander.admin;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    /** The only way a token is looked up: by its digest. The token itself is never stored. */
    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /**
     * The same lookup, holding a row lock, for redeeming.
     *
     * Single use has to survive two requests arriving together — a double-clicked
     * button, or a link opened twice. Without the lock both transactions read an
     * unused row, both set a password and both mark it used, and the second
     * password silently wins over the first; whoever typed the one that lost
     * cannot sign in and has no idea why. With it, the second waits and then
     * finds `used_at` already set.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PasswordReset r where r.tokenHash = :tokenHash")
    Optional<PasswordReset> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<PasswordReset> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PasswordReset> findByIdAndUserId(Long id, Long userId);
}
