package com.wander.trip.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTripRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 120) String destination,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        /**
         * ISO 4217, and the one field here that cannot be changed afterwards —
         * money is stored in it. Optional on the wire: omitted, it falls back to
         * the instance's configured default, so a caller that does not care about
         * currency does not have to have an opinion.
         *
         * Validated as a shape rather than against a list of known codes: an
         * operator on an instance for two people in Tbilisi should not need this
         * project to have heard of the lari.
         */
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO 4217 code")
        String currency) {
}
