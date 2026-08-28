package com.wander.enrich;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One photo candidate, with its credit.
 *
 * `author` and `licence` are non-null in the schema and there is no setter for
 * either: a candidate arrives complete or is not stored. The licence belongs to
 * the individual file — CC BY, CC BY-SA, public domain — so it cannot be inferred
 * from the source or from a sibling image.
 */
@Entity
@Table(name = "place_enrichment_photo")
public class PlaceEnrichmentPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrichment_id", nullable = false)
    private PlaceEnrichment enrichment;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "thumb_url", nullable = false, length = 500)
    private String thumbUrl;

    @Column(nullable = false, length = 300)
    private String author;

    @Column(nullable = false, length = 120)
    private String licence;

    @Column(name = "source_url", nullable = false, length = 500)
    private String sourceUrl;

    @Column(nullable = false)
    private int position;

    protected PlaceEnrichmentPhoto() {
        // JPA
    }

    PlaceEnrichmentPhoto(PlaceEnrichment enrichment, PlaceFacts.PhotoCandidate candidate,
            int position) {
        this.enrichment = enrichment;
        this.url = candidate.url();
        this.thumbUrl = candidate.thumbUrl() == null ? candidate.url() : candidate.thumbUrl();
        this.author = candidate.author();
        this.licence = candidate.licence();
        this.sourceUrl = candidate.sourceUrl() == null ? candidate.url() : candidate.sourceUrl();
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public String getThumbUrl() {
        return thumbUrl;
    }

    public String getAuthor() {
        return author;
    }

    public String getLicence() {
        return licence;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public int getPosition() {
        return position;
    }
}
