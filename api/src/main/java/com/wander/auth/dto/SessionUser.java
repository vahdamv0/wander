package com.wander.auth.dto;

import com.wander.security.WanderUser;
import com.wander.user.GlobalRole;

import jakarta.validation.constraints.NotNull;

/** What the client is told about itself. Never includes the hash. */
public record SessionUser(@NotNull Long id, @NotNull String email, @NotNull String displayName,
        @NotNull GlobalRole role,
        @NotNull boolean demoAccount) {

    public static SessionUser from(WanderUser principal, boolean demoAccount) {
        return new SessionUser(principal.id(), principal.email(), principal.displayName(),
                principal.role(), demoAccount);
    }
}
