package com.wander.common;

/**
 * Mapped to 502. A third-party service this instance depends on failed — which
 * is a different thing from this instance being broken, and the client should
 * offer a retry rather than an error page.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message) {
        super(message);
    }
}
