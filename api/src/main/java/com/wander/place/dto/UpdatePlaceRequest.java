package com.wander.place.dto;

import java.time.LocalTime;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Edits a place. Moving it between days or up and down is a separate call: this
 * one leaves the ordering untouched.
 *
 * Notes are a list here too, and written whole. There is deliberately no way to
 * edit one note: the list is the unit, so nothing has to reconcile a partially
 * applied change, and "delete the third one" is expressed by sending the list
 * without it.
 */
public record UpdatePlaceRequest(
        @NotBlank @Size(max = 160) String name,
        List<@Size(max = 2000) String> notes,
        /** The hour, when it has one. Null clears it. */
        LocalTime startsAt) {
}
