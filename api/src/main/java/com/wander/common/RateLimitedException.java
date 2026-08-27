package com.wander.common;

/** Mapped to 429. Thrown when a shared upstream budget is already spent. */
public class RateLimitedException extends RuntimeException {

    public RateLimitedException(String message) {
        super(message);
    }
}
