package com.wander.enrich;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.NotFoundException;
import com.wander.config.WanderProperties;
import com.wander.enrich.dto.PlaceEnrichmentView;
import com.wander.enrich.dto.SetPhotoRequest;
import com.wander.place.Place;
import com.wander.place.PlaceRepository;
import com.wander.place.dto.PlaceView;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripRole;

/**
 * Descriptions, facts, hours and photos for a place — fetched once, stored, and
 * shared by every trip that includes it.
 *
 * The cache is a table rather than an LRU because unlike a search, this is stable
 * data about the world: a shrine's article does not change while somebody types.
 * That also means one fetch serves every user on the instance, which is the
 * difference between a rate budget that works and one that does not.
 */
@Service
public class EnrichmentService {

    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    private final EnrichmentClient client;
    private final PlaceEnrichmentRepository enrichments;
    private final PlaceRepository places;
    private final TripAccessService access;
    private final WanderProperties.Enrichment config;
    private final Duration ttl;

    public EnrichmentService(EnrichmentClient client, PlaceEnrichmentRepository enrichments,
            PlaceRepository places, TripAccessService access, WanderProperties properties) {
        this.client = client;
        this.enrichments = enrichments;
        this.places = places;
        this.access = access;
        this.config = properties.enrichment();
        this.ttl = Duration.ofDays(Math.max(1, config.cacheDays()));
    }

    /**
     * What is known about this place, fetching it if nothing is stored or the copy
     * is stale.
     *
     * Three quite different situations return the same "nothing to show", and all
     * three are ordinary: enrichment switched off, a place typed by hand with no
     * upstream reference, and upstreams that had nothing to say.
     */
    @Transactional
    public PlaceEnrichmentView forPlace(Long userId, Long tripId, Long placeId, String language) {
        access.requireMember(tripId, userId);
        Place place = require(tripId, placeId);

        if (!config.enabled() || place.getOsmRef() == null) {
            return PlaceEnrichmentView.unavailable();
        }

        Optional<PlaceEnrichment> stored = enrichments.findByOsmRef(place.getOsmRef());
        PlaceEnrichment enrichment = stored.orElse(null);
        if (enrichment == null || enrichment.isOlderThan(ttl)) {
            PlaceFacts facts = client.fetch(place.getOsmRef(), language);
            if (enrichment == null) {
                enrichment = enrichments.save(new PlaceEnrichment(place.getOsmRef()));
            }
            // Written even when empty: "we looked and there is nothing" is worth
            // remembering, or every popup on a plain bus stop re-asks four
            // services about it.
            enrichment.replaceWith(facts);
        }
        return PlaceEnrichmentView.of(enrichment);
    }

    /**
     * Keeps one of the offered candidates against the place.
     *
     * The URL is checked against what was actually offered for this place. Not
     * because a member is untrusted, but because the alternative is an endpoint
     * that stores any URL and any licence text somebody sends — which is how an
     * instance ends up hotlinking and mis-attributing an image nobody chose.
     */
    @Transactional
    public PlaceView setPhoto(Long userId, Long tripId, Long placeId, SetPhotoRequest request) {
        access.requireRole(tripId, userId, CAN_EDIT);
        Place place = require(tripId, placeId);

        if (request.isClearing()) {
            place.setPhoto(null, null, null, null, null);
            return PlaceView.of(place);
        }

        PlaceEnrichmentPhoto offered = place.getOsmRef() == null
                ? null
                : enrichments.findByOsmRef(place.getOsmRef())
                        .flatMap(enrichment -> enrichment.getPhotos().stream()
                                .filter(photo -> photo.getUrl().equals(request.url()))
                                .findFirst())
                        .orElse(null);
        if (offered == null) {
            throw new IllegalArgumentException("url: that photo was not offered for this place");
        }

        // The stored credit is the one this instance fetched from Commons, not the
        // one the request supplied — the request is a choice, not a source.
        place.setPhoto(offered.getUrl(), offered.getThumbUrl(), offered.getAuthor(),
                offered.getLicence(), offered.getSourceUrl());
        return PlaceView.of(place);
    }

    private Place require(Long tripId, Long placeId) {
        return places.findByIdAndTripId(placeId, tripId)
                .orElseThrow(() -> new NotFoundException("Place " + placeId + " not found"));
    }
}
