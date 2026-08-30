package com.wander.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 80) String displayName,
        // Length is the only policy that matters at this size; a full policy
        // (character classes, breach-list check) belongs with invites later.
        @NotBlank @Size(min = 10, max = 200) String password,

        /**
         * An invitation token, when this sign-up came from a link. Optional, and
         * ignored entirely on an instance that accepts sign-ups anyway — it only
         * matters where self-signup is off, and then it is the whole reason the
         * account is allowed. Bounded because it is an unauthenticated string:
         * the real thing is 43 characters of URL-safe base64.
         */
        @Size(max = 200) String inviteToken) {
}
