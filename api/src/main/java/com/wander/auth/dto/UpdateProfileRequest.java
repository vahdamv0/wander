package com.wander.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The parts of your own account you can edit. One field so far.
 *
 * The email address is deliberately not here: it is the account's handle —
 * members are added to a trip by address, and an invitation is accepted against
 * one — so changing it is a rename with consequences elsewhere, not an edit to a
 * label. The display name has no such duty; it is what other people on a trip
 * see, and nothing keys off it.
 */
public record UpdateProfileRequest(@NotBlank @Size(max = 80) String displayName) {
}
