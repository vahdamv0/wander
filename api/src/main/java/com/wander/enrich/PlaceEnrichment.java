package com.wander.enrich;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * The stored answer for one place in the world.
 *
 * Keyed by `osmRef` and shared by every trip that includes that place — this row
 * is about the shrine, not about anybody's Tuesday. `fetchedAt` is both the TTL
 * and the date shown beside the opening hours, which is the only honest way to
 * present data whose freshness nobody can vouch for.
 */
@Entity
@Table(name = "place_enrichment")
public class PlaceEnrichment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "osm_ref", nullable = false, length = 40)
    private String osmRef;

    @Column(name = "wikidata_id", length = 32)
    private String wikidataId;

    @Column(length = 300)
    private String title;

    @Column(length = 2000)
    private String summary;

    @Column(name = "summary_url", length = 500)
    private String summaryUrl;

    @Column(name = "summary_licence", length = 120)
    private String summaryLicence;

    @Column(name = "opening_hours", length = 500)
    private String openingHours;

    @Column(length = 500)
    private String website;

    @Column(length = 80)
    private String phone;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    @OneToMany(mappedBy = "enrichment", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("position asc")
    private List<PlaceEnrichmentPhoto> photos = new ArrayList<>();

    protected PlaceEnrichment() {
        // JPA
    }

    public PlaceEnrichment(String osmRef) {
        this.osmRef = osmRef;
        this.fetchedAt = Instant.now();
    }

    /**
     * Overwrites everything with a fresh fetch.
     *
     * Written whole rather than merged: a place that has lost its Wikipedia
     * article should stop showing one, and a photo that has been deleted from
     * Commons should stop being offered. Keeping the old values "just in case"
     * would be inventing content nobody can now check.
     */
    public void replaceWith(PlaceFacts facts) {
        this.wikidataId = facts.wikidataId();
        this.title = facts.title();
        this.summary = facts.summary();
        this.summaryUrl = facts.summaryUrl();
        this.summaryLicence = facts.summaryLicence();
        this.openingHours = facts.openingHours();
        this.website = facts.website();
        this.phone = facts.phone();
        this.fetchedAt = Instant.now();

        photos.clear();
        int position = 0;
        for (PlaceFacts.PhotoCandidate candidate : facts.photos()) {
            // Unusable means the credit could not be read, and a picture without
            // its credit is one this project may not display at all.
            if (candidate.isUsable()) {
                photos.add(new PlaceEnrichmentPhoto(this, candidate, position++));
            }
        }
    }

    public boolean isOlderThan(java.time.Duration age) {
        return fetchedAt.isBefore(Instant.now().minus(age));
    }

    public Long getId() {
        return id;
    }

    public String getOsmRef() {
        return osmRef;
    }

    public String getWikidataId() {
        return wikidataId;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getSummaryUrl() {
        return summaryUrl;
    }

    public String getSummaryLicence() {
        return summaryLicence;
    }

    public String getOpeningHours() {
        return openingHours;
    }

    public String getWebsite() {
        return website;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public List<PlaceEnrichmentPhoto> getPhotos() {
        return photos;
    }
}
