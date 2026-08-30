package com.wander.auth;

/**
 * The current password given to a password change did not match.
 *
 * Deliberately **not** an {@code AuthenticationException}: that maps to 401, and
 * a 401 is what the client treats as "your session is gone" — it would clear the
 * cached identity and bounce somebody to the login page for a typo. This is a
 * bad field in an otherwise perfectly authenticated request, so it is a 400.
 */
public class IncorrectPasswordException extends RuntimeException {

    public IncorrectPasswordException() {
        super("Your current password is not right");
    }
}
