package com.wander.enrich.dto;

import com.wander.enrich.PlaceEnrichmentPhoto;

import jakarta.validation.constraints.NotNull;

/**
 * One photo on offer. Every field is required, because a picture without its
 * author, licence and source is one that may not be shown at all — so there is no
 * shape of this record that represents an unattributed image.
 */
public record PhotoCandidateView(
        @NotNull String url,
        @NotNull String thumbUrl,
        @NotNull String author,
        @NotNull String licence,
        @NotNull String sourceUrl) {

    public static PhotoCandidateView of(PlaceEnrichmentPhoto photo) {
        return new PhotoCandidateView(photo.getUrl(), photo.getThumbUrl(), photo.getAuthor(),
                photo.getLicence(), photo.getSourceUrl());
    }
}
