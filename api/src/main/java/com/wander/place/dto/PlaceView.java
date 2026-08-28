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
        String notes,
        /** Null unless the place came from a search — both coordinates or neither. */
        Double latitude,
        Double longitude,
        /** The geocoder's formatted address line, when there was one. */
        String address,
        /**
         * True when this place came from a search and can therefore be asked
         * about. The reference itself is not sent — the client never needs it,
         * and it is the server that does the asking.
         */
        @NotNull boolean enrichable,
        /**
         * A photo somebody kept, if any. The credit travels with it because it
         * has to be shown wherever the picture is: a Commons image is licensed
         * per image, and the terms for one say nothing about the next.
         */
        String photoThumbUrl,
        String photoUrl,
        String photoAuthor,
        String photoLicence,
        String photoSourceUrl) {

    public static PlaceView of(Place place) {
        return new PlaceView(place.getId(), place.getDayDate(), place.getSortOrder(), place.getName(),
                place.getNotes(), place.getLatitude(), place.getLongitude(), place.getAddress(),
                place.getOsmRef() != null,
                place.getPhotoThumbUrl(), place.getPhotoUrl(), place.getPhotoAuthor(),
                place.getPhotoLicence(), place.getPhotoSourceUrl());
    }
}
