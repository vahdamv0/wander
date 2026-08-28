package com.wander.trip.dto;

import com.wander.trip.TripRole;

import jakarta.validation.constraints.NotNull;

/**
 * A member's new role. OWNER here means a transfer: the target becomes the
 * owner and the caller, who was, becomes an EDITOR.
 */
public record ChangeRoleRequest(@NotNull TripRole role) {
}
