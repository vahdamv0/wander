package com.wander.auth;

/**
 * The new password offered to a password change was the one already in use.
 */
public class SamePasswordException extends RuntimeException {

    public SamePasswordException() {
        super("The new password is the same as the current one");
    }
}
