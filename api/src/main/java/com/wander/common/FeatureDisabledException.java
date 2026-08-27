package com.wander.common;

/**
 * Mapped to 503. The operator turned this feature off — an instance with no
 * outbound network access will run with place search disabled, and the client
 * needs to tell that apart from a search that found nothing.
 */
public class FeatureDisabledException extends RuntimeException {

    public FeatureDisabledException(String message) {
        super(message);
    }
}
