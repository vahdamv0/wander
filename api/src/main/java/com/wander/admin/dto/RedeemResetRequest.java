package com.wander.admin.dto;

import com.wander.auth.GuessablePassword;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Set a new password using a reset link.
 *
 * No current password, which is the whole point of the link — its holder does
 * not have one. The token is the credential, which is why it is 256 bits, hashed
 * at rest, single use and short-lived.
 *
 * The password rules are the same ones registration and change-password apply,
 * for the reason stated on {@code ChangePasswordRequest}: a policy that covers
 * some of the doors is the door people use to get a weak password through.
 */
public record RedeemResetRequest(
        @NotBlank @Size(min = 10, max = 200) @GuessablePassword String newPassword) {
}
