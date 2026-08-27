package com.wander.common;

import java.time.Instant;
import java.util.Map;

/**
 * The single error envelope every failure comes back in, so the client has one
 * shape to handle. `fields` is populated only for validation failures.
 */
public record ApiError(int status, String error, String message, Map<String, String> fields, Instant timestamp) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(status, error, message, Map.of(), Instant.now());
    }
}
