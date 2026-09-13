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

    /**
     * Whether an auto-sort has to leave this place where it is.
     *
     * A position rather than a preference: a locked place keeps its index and
     * the optimiser fits the rest around it, which is how a hotel or a booked
     * table stays put without the server knowing what either of those is. It
     * constrains nothing a person does — dragging a locked place is fine.
     */
    @Column(nullable = false)
    private boolean locked;

    @Column(nullable = false)
    private String name;

    /**
     * Notes, in order, replaced as a set. `Place` owns them outright — a note
     * without its place is meaningless — so the association cascades and orphans
     * are removed, which is also what makes deleting a place take them with it.
     */
    @jakarta.persistence.OneToMany(mappedBy = "place",
            cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @jakarta.persistence.OrderBy("position asc")
    private java.util.List<PlaceNote> notes = new java.util.ArrayList<>();

    /**
     * The time of day, or null for something with no particular hour.
     *
     * A plain time: the day comes from `dayDate` and the zone from where the place
     * is, so neither needs storing. Not a sort key either — see V13.
     */
    @Column(name = "starts_at")
    private java.time.LocalTime startsAt;

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
     * The geocoder's own classification — "attraction", "restaurant" — or null for
     * a place typed by hand. Never inferred from the name: a category shown as
     * fact should have come from somewhere that knows.
     */
    @Column(length = 40)
    private String category;

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

    public Place(Trip trip, LocalDate dayDate, int sortOrder, String name) {
        this.trip = trip;
        this.dayDate = dayDate;
        this.sortOrder = sortOrder;
        this.name = name;
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

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public java.util.List<PlaceNote> getNotes() {
        return notes;
    }

    /**
     * Replaces the notes with the given bodies, in order.
     *
     * Rows for positions that already exist are reused rather than deleted and
     * recreated. Not an optimisation: `Expense.replaceShares` had to learn the
     * same thing, because Hibernate orders inserts before orphan deletes inside
     * one flush and a delete-then-insert of the same row trips any constraint on
     * it. There is no unique constraint here today, but the reuse also keeps note
     * ids stable, which anything referring to one later would want.
     */
    public void replaceNotes(java.util.List<String> bodies) {
        while (notes.size() > bodies.size()) {
            notes.remove(notes.size() - 1);
        }
        for (int i = 0; i < bodies.size(); i++) {
            if (i < notes.size()) {
                notes.get(i).setBody(bodies.get(i));
                notes.get(i).setPosition(i);
            } else {
                notes.add(new PlaceNote(this, bodies.get(i), i));
            }
        }
    }

    public java.time.LocalTime getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(java.time.LocalTime startsAt) {
        this.startsAt = startsAt;
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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
