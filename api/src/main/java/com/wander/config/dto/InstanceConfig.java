package com.wander.config.dto;

import jakarta.validation.constraints.NotNull;

/**
 * What the client needs to know about this instance before it draws anything.
 *
 * It exists because wander is self-hosted: the tile server, and whether place
 * search works at all, are the operator's decisions, and a client that guessed
 * at them would be wrong on somebody's instance. Everything here is a setting,
 * never user data.
 */
public record InstanceConfig(
        /** False on an instance with no outbound network — the client hides its search box. */
        @NotNull boolean searchEnabled,
        @NotNull MapConfig map) {
}
