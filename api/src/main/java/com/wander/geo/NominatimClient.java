package com.wander.geo;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.wander.common.UpstreamUnavailableException;
import com.wander.config.WanderProperties;
import com.wander.geo.dto.PlaceSuggestion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nominatim over HTTP.
 *
 * The response is walked as a tree rather than bound to a record. That is on
 * purpose: this is a foreign schema that can gain and lose fields without
 * warning, and naming the three fields we actually read at the point we read
 * them is clearer than a DTO full of snake_case aliases that must stay in step.
 *
 * Two things the usage policy requires and this class does: a User-Agent that
 * identifies the application (a default Java one gets blocked), and no more than
 * one request a second — the rate gate that enforces that lives in
 * {@link GeocodingService}, which is the only caller.
 */
@Component
public class NominatimClient implements GeocoderClient {

    private static final Logger log = LoggerFactory.getLogger(NominatimClient.class);

    private final RestClient http;
    private final ObjectMapper json;

    public NominatimClient(WanderProperties properties, ObjectMapper json) {
        this.json = json;
        // RestClient.builder() rather than an injected RestClient.Builder: Boot 4
        // only autoconfigures that bean when spring-boot-restclient is on the
        // classpath, and one outbound call is not worth another module.
        this.http = RestClient.builder()
                .baseUrl(properties.geocoding().baseUrl())
                // Identifying the caller is a condition of using the public
                // instance; without it requests come back 403, politely.
                .defaultHeader("User-Agent", userAgent(properties))
                .defaultHeader("Accept", "application/json")
                // Timeouts on this client only: a slow geocoder must not sit on
                // a request thread, and a global RestClientCustomizer would have
                // changed every other RestClient in the application too.
                .requestFactory(timeouts())
                .build();
    }

    private static JdkClientHttpRequestFactory timeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(6));
        return factory;
    }

    private static String userAgent(WanderProperties properties) {
        String contact = properties.geocoding().contactEmail();
        return contact.isBlank()
                ? "wander/" + properties.version() + " (self-hosted travel planner)"
                : "wander/" + properties.version() + " (" + contact + ")";
    }

    @Override
    public List<PlaceSuggestion> search(String query, int limit) {
        String body;
        try {
            body = http.get()
                    .uri(uri -> searchUri(uri, query, limit))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException ex) {
            // A geocoder being down, slow, or rate-limiting us is normal
            // operation, not a bug in this instance — 502, and the detail stays
            // in the log rather than going to the browser.
            log.warn("Geocoder request failed: {}", ex.getMessage());
            throw new UpstreamUnavailableException("The place search service did not answer");
        }
        return parse(body);
    }

    private static URI searchUri(UriBuilder uri, String query, int limit) {
        return uri.path("/search")
                // jsonv2 is the documented stable format; `format=json` is the
                // legacy one and names some fields differently.
                .queryParam("format", "jsonv2")
                .queryParam("q", query)
                .queryParam("limit", limit)
                // Gives us the pretty `name` separately from the full line.
                .queryParam("addressdetails", 0)
                .build();
    }

    /** Package-private so the mapping can be tested without an HTTP round trip. */
    List<PlaceSuggestion> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException ex) {
            log.warn("Geocoder returned something that is not JSON: {}", ex.getMessage());
            throw new UpstreamUnavailableException("The place search service answered unexpectedly");
        }

        List<PlaceSuggestion> suggestions = new ArrayList<>();
        for (JsonNode hit : root) {
            String address = text(hit, "display_name");
            Double latitude = decimal(hit, "lat");
            Double longitude = decimal(hit, "lon");
            // A hit with no point is useless to a map and to a saved place.
            if (address == null || latitude == null || longitude == null) {
                continue;
            }
            String name = text(hit, "name");
            suggestions.add(new PlaceSuggestion(
                    reference(hit),
                    // Some results (an address, a postcode) have no short name;
                    // the first segment of the full line reads best there.
                    name != null && !name.isBlank() ? name : firstSegment(address),
                    address,
                    latitude,
                    longitude,
                    text(hit, "type")));
        }
        return List.copyOf(suggestions);
    }

    /** {@code osm_type/osm_id}, falling back to the coordinates when absent. */
    private static String reference(JsonNode hit) {
        String type = text(hit, "osm_type");
        JsonNode id = hit.get("osm_id");
        if (type != null && id != null && !id.isNull()) {
            return type + "/" + id.asString();
        }
        return text(hit, "lat") + "," + text(hit, "lon");
    }

    private static String firstSegment(String address) {
        int comma = address.indexOf(',');
        return comma > 0 ? address.substring(0, comma).trim() : address;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** Nominatim sends coordinates as strings, so this parses rather than casts. */
    private static Double decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
