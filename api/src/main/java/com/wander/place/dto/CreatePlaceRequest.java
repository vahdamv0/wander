package com.wander.place.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Appends to the end of the given day. */
public record CreatePlaceRequest(
        @NotNull LocalDate dayDate,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2000) String notes) {
}
