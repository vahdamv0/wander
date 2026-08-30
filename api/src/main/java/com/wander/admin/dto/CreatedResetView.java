package com.wander.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The one and only time the token is readable.
 *
 * The server keeps a digest, so this is not a policy that could be relaxed later
 * by adding a getter — the same shape as {@code CreatedInviteView}, and the same
 * bargain as the first-boot admin password printed once to the log. The client's
 * job is to put the link in front of the administrator immediately and say that
 * a refresh loses it; the answer after that is to revoke it and mint another.
 *
 * `path` rather than a full URL, because behind a proxy the server sees an
 * internal host and a link built from it goes nowhere. The browser knows its own
 * origin and joins the two.
 */
public record CreatedResetView(
        @NotNull PasswordResetView reset,
        @NotNull String token,
        @NotNull String path) {
}
