package com.wander.trip.dto;

import com.wander.trip.TripRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Add somebody who already has an account on this instance.
 *
 * An email rather than a user id: the owner knows the address they invited
 * somebody by, and exposing a user-id space to pick from would need a user
 * directory endpoint, which is a much bigger thing to make safe.
 *
 * `role` may not be OWNER — a trip has exactly one, and the only way to change
 * who it is is a transfer through the role endpoint.
 */
public record AddMemberRequest(
        @NotBlank @Email String email,
        @NotNull TripRole role) {
}
