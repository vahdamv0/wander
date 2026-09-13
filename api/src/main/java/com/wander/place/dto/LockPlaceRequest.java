package com.wander.place.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Locking one place to its position.
 *
 * Its own endpoint and its own one-field body, the argument `PackedRequest`
 * already makes: sending the whole place to flip a boolean lets a lock quietly
 * undo a rename that arrived in between, and on a trip two people are editing
 * that is not a hypothetical.
 */
public record LockPlaceRequest(@NotNull Boolean locked) {
}
