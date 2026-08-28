package com.wander.place.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Appends to the end of the given day.
 *
 * The location fields are what a search result contributes; a place typed by
 * hand simply omits them. They are accepted from the client rather than
 * re-geocoded server-side because the user picked one specific candidate out of
 * several, and searching again could rank a different one first.
 */
public record CreatePlaceRequest(
        @NotNull LocalDate dayDate,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2000) String notes,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @Size(max = 500) String address,
        /**
         * The geocoder's own reference for the picked suggestion, e.g.
         * `node/240109189`. Sent by the client for the same reason the
         * coordinates are — it came back with the hit the user chose, and
         * re-deriving it later would mean guessing which of several candidates
         * they meant. Absent for a place typed by hand, permanently.
         */
        @Size(max = 40) String osmRef) {
}
