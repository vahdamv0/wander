package com.wander.place.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Edits the text of a place. Moving it between days or up and down is a
 * separate call: this one leaves the ordering untouched.
 */
public record UpdatePlaceRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2000) String notes) {
}
