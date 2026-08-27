package com.wander.place.dto;

import java.time.LocalDate;

import com.wander.place.Place;

import jakarta.validation.constraints.NotNull;

/** One place on one day. `position` is the API's name for the stored sort order. */
public record PlaceView(
        @NotNull Long id,
        @NotNull LocalDate dayDate,
        @NotNull int position,
        @NotNull String name,
        /** Nullable on purpose: most places never get a note. */
        String notes) {

    public static PlaceView of(Place place) {
        return new PlaceView(place.getId(), place.getDayDate(), place.getSortOrder(), place.getName(),
                place.getNotes());
    }
}
