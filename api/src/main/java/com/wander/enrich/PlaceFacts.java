package com.wander.enrich;

import java.util.List;

/**
 * What the upstreams between them know about one place.
 *
 * The shape {@link EnrichmentClient} returns and the shape stored — no DTO
 * translation in between, because there is nothing to translate: this is already
 * the union of four services reduced to what a traveller would want.
 *
 * Every text field is nullable. Most places in OpenStreetMap have a name and
 * nothing else, and "we found nothing" is the ordinary answer rather than a
 * failure.
 */
public record PlaceFacts(
        String wikidataId,
        String title,
        String summary,
        /** Where the summary came from, and under what terms. Both or neither. */
        String summaryUrl,
        String summaryLicence,
        /** OpenStreetMap's raw specification. Never parsed — see V11. */
        String openingHours,
        String website,
        String phone,
        List<PhotoCandidate> photos) {

    public static PlaceFacts empty() {
        return new PlaceFacts(null, null, null, null, null, null, null, null, List.of());
    }

    /**
     * A picture, and the credit that has to travel with it.
     *
     * `author` and `licence` are required by construction: a candidate whose
     * attribution could not be read is dropped rather than offered, because this
     * project has no right to display it.
     */
    public record PhotoCandidate(String url, String thumbUrl, String author, String licence,
            String sourceUrl) {

        public boolean isUsable() {
            return url != null && !url.isBlank()
                    && author != null && !author.isBlank()
                    && licence != null && !licence.isBlank();
        }
    }
}
