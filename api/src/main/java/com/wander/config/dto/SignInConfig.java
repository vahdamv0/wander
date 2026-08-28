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
 */
public record SignInConfig(@NotNull boolean registrationEnabled) {
}
