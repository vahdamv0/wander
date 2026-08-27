package com.wander.trip.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTripRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 120) String destination,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate) {
}
