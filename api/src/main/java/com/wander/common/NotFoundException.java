package com.wander.common;

/**
 * 404. Also what a caller gets for a resource they simply may not see — see
 * TripAccessService for why that is not a 403.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
