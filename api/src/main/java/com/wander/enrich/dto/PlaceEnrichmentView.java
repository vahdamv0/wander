package com.wander.enrich.dto;

import java.time.Instant;
import java.util.List;

import com.wander.enrich.PlaceEnrichment;

import jakarta.validation.constraints.NotNull;

/**
 * What is known about a place, for the popup.
 *
 * `available: false` covers three different situations that all look the same from
 * the client's side and none of which is an error: the instance has enrichment
 * switched off, the place was typed by hand and has no upstream reference, or the
 * upstreams simply had nothing. The client draws nothing in all three.
 *
 * Nothing here is authoritative and the DTO says so structurally: the summary
 * carries its own source and licence, and the hours carry the date they were
 * fetched, because that is the only honest way to show data whose freshness
 * nobody can vouch for.
 */
public record PlaceEnrichmentView(
        @NotNull boolean available,
        String title,
        String summary,
        String summaryUrl,
        String summaryLicence,
        /** OpenStreetMap's raw specification, never parsed into "open now". */
        String openingHours,
        String website,
        String phone,
        /** When this was last fetched — shown beside the hours, as their provenance. */
        Instant fetchedAt,
        @NotNull List<PhotoCandidateView> photos) {

    public static PlaceEnrichmentView unavailable() {
        return new PlaceEnrichmentView(false, null, null, null, null, null, null, null, null,
                List.of());
    }

    public static PlaceEnrichmentView of(PlaceEnrichment enrichment) {
        return new PlaceEnrichmentView(true, enrichment.getTitle(), enrichment.getSummary(),
                enrichment.getSummaryUrl(), enrichment.getSummaryLicence(),
                enrichment.getOpeningHours(), enrichment.getWebsite(), enrichment.getPhone(),
                enrichment.getFetchedAt(),
                enrichment.getPhotos().stream().map(PhotoCandidateView::of).toList());
    }
}
