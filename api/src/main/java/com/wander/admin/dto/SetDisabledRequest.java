package com.wander.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Take an account out of service, or put it back.
 *
 * One endpoint carrying the desired state rather than two verbs, so a client
 * that has fallen behind cannot toggle an account it thought was in the other
 * state.
 */
public record SetDisabledRequest(@NotNull Boolean disabled) {
}
