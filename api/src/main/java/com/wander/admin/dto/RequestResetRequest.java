package com.wander.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ask for a password reset link by email.
 *
 * The address is validated for *shape* only, and that is the one thing this
 * endpoint is allowed to be picky about: a 400 for "that is not an email
 * address" tells a caller nothing about who has an account here, whereas any
 * distinction drawn later — found, not found, disabled — would. Everything past
 * this point answers 204.
 */
public record RequestResetRequest(@NotBlank @Email @Size(max = 320) String email) {
}
