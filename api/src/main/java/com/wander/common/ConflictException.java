package com.wander.common;

/**
 * The request was understood and permitted, but the current state will not have
 * it: adding a member who is already one, removing the owner of a trip.
 *
 * Distinct from IllegalArgumentException (400) on purpose — nothing about the
 * request is malformed, so retrying it unchanged after the state moves is
 * reasonable.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
