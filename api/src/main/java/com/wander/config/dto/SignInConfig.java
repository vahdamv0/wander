package com.wander.config.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The little that the sign-in page needs before anybody is signed in.
 *
 * Separate from {@link InstanceConfig} rather than a field on it, because the
 * two have different audiences: the tile URL and the search toggle are read by
 * the signed-in shell, and putting them behind one anonymous endpoint would
 * publish an operator's whole configuration to any caller.
 *
 * `registrationEnabled` leaks nothing that was not already visible — an
 * anonymous caller can learn the same thing by posting to /api/auth/register and
 * reading the 403 — and hiding it would only mean offering people a form that is
 * guaranteed to refuse them.
 *
 * `sourceUrl` is here for a different reason, and it is the licence. AGPL-3.0
 * section 13 owes source to everybody *interacting with the instance over a
 * network*, and on a public one most of those people never sign in — they reach
 * the login page and stop. A link that only exists behind authentication would
 * miss exactly the audience the clause is written for. It is also not a secret
 * by any reading: it is a link this instance wants people to follow.
 */
public record SignInConfig(
        @NotNull boolean registrationEnabled,
        /** Empty when the operator has cleared it; the client then draws nothing. */
        @NotNull String sourceUrl,
        /**
         * The published demo account, or empty on an instance without one.
         *
         * A password on a public endpoint reads like a mistake, so it is worth
         * saying why it is not: this credential is *meant* to be published — it
         * is printed on the sign-in page so a visitor can get in — and it belongs
         * to an account that is a **viewer** on one seeded trip. There is nothing
         * to keep back. What makes it safe is not secrecy but the role, which
         * {@code TripAccessService} enforces like anybody else's.
         *
         * Both are empty unless {@code wander.demo.enabled} is on, so an ordinary
         * instance publishes nothing at all here.
         */
        @NotNull String demoEmail,
        @NotNull String demoPassword,
        /**
         * Whether this instance can mail somebody a reset link.
         *
         * The login page needs it to decide whether to draw "Forgot password?",
         * and drawing it on an instance that cannot send is worse than not
         * drawing it at all: the one person who clicks it is, by definition,
         * already locked out, and a form that quietly does nothing is how they
         * learn there is no way back.
         *
         * It publishes nothing an anonymous caller could not establish by
         * posting to the endpoint and reading the 404. Note this follows
         * `registrationEnabled`'s rule rather than the rest of the config's:
         * "not answered yet" must read as *un*available, or the link flickers
         * into existence and out again as the page loads.
         */
        @NotNull boolean passwordResetEnabled) {
}
