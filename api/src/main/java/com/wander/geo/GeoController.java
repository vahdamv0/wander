package com.wander.geo;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wander.geo.dto.PlaceSuggestion;

import io.swagger.v3.oas.annotations.Parameter;

/**
 * Place search, proxied.
 *
 * The browser never talks to the geocoder directly, for three reasons: the usage
 * policy wants one identifiable caller with a rate limit rather than one per
 * visitor, a shared cache only works server-side, and a self-hoster can point
 * the whole instance at their own Nominatim without every client learning a new
 * URL.
 *
 * Authenticated like everything else — an open proxy would hand this instance's
 * rate budget to anyone who found it.
 */
@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private final GeocodingService geocoding;

    public GeoController(GeocodingService geocoding) {
        this.geocoding = geocoding;
    }

    /**
     * The caller's {@code Accept-Language} decides what language the results come
     * back in — without it a geocoder answers in the place's own language, so
     * searching Kyoto returns 京都.
     *
     * The header is hidden from the OpenAPI document on purpose: the browser sets
     * it itself and JavaScript is forbidden from touching it, so a generated
     * client parameter for it could only ever be wrong.
     */
    @GetMapping("/search")
    public List<PlaceSuggestion> searchPlaces(@RequestParam("q") String query,
            @RequestParam(name = "limit", required = false) Integer limit,
            @Parameter(hidden = true) @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE,
                    required = false) String acceptLanguage) {
        return geocoding.search(query, limit, acceptLanguage);
    }
}
