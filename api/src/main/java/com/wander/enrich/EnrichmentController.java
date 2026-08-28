package com.wander.enrich;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wander.config.WanderProperties;
import com.wander.enrich.dto.PlaceEnrichmentView;
import com.wander.enrich.dto.SetPhotoRequest;
import com.wander.place.dto.PlaceView;
import com.wander.security.WanderUser;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;

/**
 * What is known about one place, and which picture to keep.
 *
 * `Accept-Language` decides which Wikipedia is asked, for the same reason it
 * decides the geocoder's language — and it is hidden from the OpenAPI document
 * for the same reason too: browsers set it themselves and JavaScript may not
 * touch it, so a generated client parameter could only ever be wrong.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/places/{placeId}")
public class EnrichmentController {

    private final EnrichmentService enrichment;
    private final WanderProperties properties;

    public EnrichmentController(EnrichmentService enrichment, WanderProperties properties) {
        this.enrichment = enrichment;
        this.properties = properties;
    }

    @GetMapping("/enrichment")
    public PlaceEnrichmentView getPlaceEnrichment(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long placeId,
            @Parameter(hidden = true) @RequestHeader(value = "Accept-Language", required = false)
            String acceptLanguage) {
        return enrichment.forPlace(principal.id(), tripId, placeId, language(acceptLanguage));
    }

    @PutMapping("/photo")
    public PlaceView setPlacePhoto(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long placeId,
            @Valid @RequestBody SetPhotoRequest request) {
        return enrichment.setPhoto(principal.id(), tripId, placeId, request);
    }

    /**
     * The first tag of the header, reduced to a language subtag — `en-GB,en;q=0.9`
     * is the `en` Wikipedia. Falls back to the instance's configured language,
     * which is never blank for exactly this reason.
     */
    private String language(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return properties.geocoding().language();
        }
        String first = acceptLanguage.split(",")[0].split(";")[0].trim();
        String subtag = first.split("-")[0].toLowerCase();
        return subtag.matches("[a-z]{2,3}") ? subtag : properties.geocoding().language();
    }
}
