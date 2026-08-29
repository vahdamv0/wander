package com.wander.trip.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The one and only time the token is readable.
 *
 * Returned by the create call and never obtainable again — the server keeps a
 * digest, so this is not a policy that could be relaxed later by adding a getter.
 * The client's job is to put it in front of the owner immediately, because a page
 * refresh loses it and the answer is then "revoke it and make another".
 *
 * `path` rather than a full URL: the server does not reliably know the address a
 * browser reached it on — behind the proxy it sees an internal host — and a link
 * built from the wrong one is a link that goes nowhere. The client knows its own
 * origin, so it joins the two.
 */
public record CreatedInviteView(
        @NotNull TripInviteView invite,
        @NotNull String token,
        @NotNull String path) {
}
