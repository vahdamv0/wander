package com.wander.admin.dto;

import java.time.Instant;

import com.wander.user.GlobalRole;
import com.wander.user.User;

import jakarta.validation.constraints.NotNull;

/**
 * One account, as an administrator sees it.
 *
 * **No password hash, and no reset token.** An admin is a person with authority
 * over accounts, not a person entitled to their credentials — the hash is not
 * theirs to read and the token is returned exactly once, by
 * {@link CreatedResetView}, so it could not be put here even if this record
 * asked for it.
 *
 * The email address is here because it is the account's handle: it is what
 * somebody is added to a trip by, and an admin asked to help "the person whose
 * address is x" needs to find them by it.
 */
public record AdminUserView(
        @NotNull Long id,
        @NotNull String email,
        @NotNull String displayName,
        @NotNull GlobalRole role,
        @NotNull Instant createdAt,
        /** True when the account has been taken out of service. */
        @NotNull Boolean disabled,
        /** When that happened; null for an account in normal use. */
        Instant disabledAt) {

    public static AdminUserView of(User user) {
        return new AdminUserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getCreatedAt(), user.isDisabled(), user.getDisabledAt());
    }
}
