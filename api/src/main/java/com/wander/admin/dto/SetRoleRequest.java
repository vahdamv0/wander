package com.wander.admin.dto;

import com.wander.user.GlobalRole;

import jakarta.validation.constraints.NotNull;

/**
 * Promote an account to administrator, or demote one back.
 *
 * The desired state rather than a verb, like {@link SetDisabledRequest} and for
 * the same reason: a client working from a list it read a minute ago cannot
 * toggle an account into the opposite of what it meant.
 */
public record SetRoleRequest(@NotNull GlobalRole role) {
}
