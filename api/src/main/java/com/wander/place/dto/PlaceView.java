package com.wander.place.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.wander.place.Place;
import com.wander.place.PlaceNote;

import jakarta.validation.constraints.NotNull;

/** One place on one day. `position` is the API's name for the stored sort order. */
public record PlaceView(
        @NotNull Long id,
        @NotNull LocalDate dayDate,
        @NotNull int position,
        @NotNull String name,
        /** In order, and usually empty: most places never get a note. */
        @NotNull List<String> notes,
        /**
         * True when an auto-sort has to leave this place at this position. See
         * {@code DayRouteOptimiser}: it is a pinned index, not a hint.
         */
        @NotNull boolean locked,
        /** The hour, when the place has one. */
        LocalTime startsAt,
        /** Null unless the place came from a search — both coordinates or neither. */
        Double latitude,
        Double longitude,
        /** The geocoder's formatted address line, when there was one. */
        String address,
        /** The geocoder's classification, when there was one. Null for a typed place. */
        String category,
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
                place.getNotes().stream().map(PlaceNote::getBody).toList(), place.isLocked(),
                place.getStartsAt(),
                place.getLatitude(), place.getLongitude(), place.getAddress(),
                place.getCategory(), place.getOsmRef() != null,
                place.getPhotoThumbUrl(), place.getPhotoUrl(), place.getPhotoAuthor(),
                place.getPhotoLicence(), place.getPhotoSourceUrl());
    }
}
