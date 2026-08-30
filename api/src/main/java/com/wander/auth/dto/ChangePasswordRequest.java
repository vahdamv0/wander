package com.wander.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A signed-in user changing their own password.
 *
 * The current password is required even though the caller already holds a
 * session, and that is the whole security value of this endpoint: a session
 * cookie somebody else has got hold of should not be enough to take the account
 * permanently. Knowing the password is what separates the owner from a borrowed
 * browser.
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,

        // The same bound as registration, and for the same reason: length is
        // the only policy that earns its keep at this size.
        @NotBlank @Size(min = 10, max = 200) String newPassword) {
}
