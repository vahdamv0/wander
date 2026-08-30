package com.wander.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * What the holder of a reset link is shown before they type a new password.
 *
 * This is **anonymous**, unlike the invitation preview it is otherwise modelled
 * on, and it has to be: the entire audience for this page is somebody who cannot
 * sign in. That makes it the second public endpoint in this application after
 * `/api/config/sign-in`, and the reason is written down in
 * {@code PasswordResetController}.
 *
 * It carries the account's email address, which sounds like a leak and is not: a
 * caller who holds a live 256-bit token can set that account's password
 * regardless, so the address tells them nothing the token did not already give
 * them — and without it the page cannot say *which* account is being reset,
 * which is the one thing somebody sent a link out of the blue needs to know.
 *
 * Answers for a token that exists but is spent, revoked or expired, because
 * "this link has already been used, ask for another" is the message that lets
 * the holder do something about it. A token that does not exist is a 404, always
 * — see the controller.
 */
public record ResetPreview(
        @NotNull String email,
        @NotNull String displayName,
        @NotNull Boolean usable,
        @NotNull String reason) {
}
