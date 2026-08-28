package com.wander.place;

import java.time.Instant;
import java.time.LocalDate;

import com.wander.trip.Trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "places")
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    /**
     * The day this place sits on, as a date. Days are derived from the trip's
     * range, so there is no day row to reference.
     */
    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    /** Rank within its day, dense and zero-based. The service owns renumbering. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private String name;

    @Column
    private String notes;

    /**
     * Where the place is, when it came from a search. Null for one typed by
     * hand, and null in both columns or neither — a database CHECK enforces the
     * pair, since half a point is not a location.
     */
    @Column
    private Double latitude;

    @Column
    private Double longitude;

    /** The geocoder's own formatted address line, kept verbatim. */
    @Column
    private String address;

    /**
     * The geocoder's own reference for this place — `node/240109189` — or null for
     * one typed by hand.
     *
     * Kept so the place can be asked about later: everything an enrichment knows
     * hangs off this, and matching back by name and coordinates instead would be
     * a guess that sometimes describes the building next door.
     */
    @Column(name = "osm_ref", length = 40)
    private String osmRef;

    /**
     * A photo chosen from the candidates, with the credit that has to appear
     * wherever it does. The five move together — see {@link #setPhoto}.
     */
    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "photo_thumb_url", length = 500)
    private String photoThumbUrl;

    @Column(name = "photo_author", length = 300)
    private String photoAuthor;

    @Column(name = "photo_licence", length = 120)
    private String photoLicence;

    @Column(name = "photo_source_url", length = 500)
    private String photoSourceUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Place() {
        // JPA
    }

    public Place(Trip trip, LocalDate dayDate, int sortOrder, String name, String notes) {
        this.trip = trip;
        this.dayDate = dayDate;
        this.sortOrder = sortOrder;
        this.name = name;
        this.notes = notes;
        this.createdAt = Instant.now();
    }

    /** Sets both coordinates or neither, mirroring the CHECK on the table. */
    public void setLocation(Double latitude, Double longitude, String address) {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("latitude and longitude must be given together");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public LocalDate getDayDate() {
        return dayDate;
    }

    public void setDayDate(LocalDate dayDate) {
        this.dayDate = dayDate;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public String getAddress() {
        return address;
    }

    public String getOsmRef() {
        return osmRef;
    }

    public void setOsmRef(String osmRef) {
        this.osmRef = osmRef;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public String getPhotoThumbUrl() {
        return photoThumbUrl;
    }

    public String getPhotoAuthor() {
        return photoAuthor;
    }

    public String getPhotoLicence() {
        return photoLicence;
    }

    public String getPhotoSourceUrl() {
        return photoSourceUrl;
    }

    /**
     * All five together, or all five cleared. A URL without its author and licence
     * is a picture this project has no right to display, so there is deliberately
     * no way to set one without the other four.
     */
    public void setPhoto(String url, String thumbUrl, String author, String licence,
            String sourceUrl) {
        if (url == null) {
            this.photoUrl = null;
            this.photoThumbUrl = null;
            this.photoAuthor = null;
            this.photoLicence = null;
            this.photoSourceUrl = null;
            return;
        }
        if (author == null || author.isBlank() || licence == null || licence.isBlank()) {
            throw new IllegalArgumentException("A photo needs its author and licence");
        }
        this.photoUrl = url;
        this.photoThumbUrl = thumbUrl == null ? url : thumbUrl;
        this.photoAuthor = author;
        this.photoLicence = licence;
        this.photoSourceUrl = sourceUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
