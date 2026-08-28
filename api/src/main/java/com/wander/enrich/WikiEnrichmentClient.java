package com.wander.enrich;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.wander.config.WanderProperties;
import com.wander.geo.RateGate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The chain: OpenStreetMap's tags, then Wikidata, then Wikipedia, then Commons.
 *
 * Each step only happens if the one before it produced something to go on, which
 * is why this is one class rather than four services orchestrated from above —
 * most places stop at step one, having no `wikidata` tag, and that is a complete
 * and ordinary answer.
 *
 * Two rate gates, both injected. The OSM lookup shares the **same gate as place
 * search**, because Nominatim's one-request-a-second is owed by the instance and
 * not by the feature. Wikimedia gets its own, gentler one.
 *
 * Nothing here throws on a bad answer from upstream. An enrichment is a nicety:
 * a Wikipedia outage should cost a description, not a 502 on somebody's
 * itinerary. Failures are logged and the field comes back null.
 */
@Component
public class WikiEnrichmentClient implements EnrichmentClient {

    private static final Logger log = LoggerFactory.getLogger(WikiEnrichmentClient.class);

    /** CC BY-SA 4.0, which is what article text is under and has to be shown with it. */
    private static final String WIKIPEDIA_LICENCE = "CC BY-SA 4.0";

    private final WanderProperties properties;
    private final WanderProperties.Enrichment config;
    private final ObjectMapper json;
    private final RateGate nominatimGate;
    private final RateGate wikimediaGate;
    private final RestClient nominatim;
    private final RestClient wikidata;
    private final RestClient commons;

    public WikiEnrichmentClient(WanderProperties properties, ObjectMapper json,
            RateGate nominatimGate, RateGate wikimediaGate) {
        this.properties = properties;
        this.config = properties.enrichment();
        this.json = json;
        this.nominatimGate = nominatimGate;
        this.wikimediaGate = wikimediaGate;
        this.nominatim = client(properties.geocoding().baseUrl());
        this.wikidata = client(config.wikidataUrl());
        this.commons = client(config.commonsUrl());
    }

    private RestClient client(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(6));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                // The same identifying User-Agent the geocoder uses. Wikimedia's
                // etiquette asks for one too, and a default Java agent is how an
                // instance gets blocked without being told why.
                .defaultHeader("User-Agent", userAgent())
                .build();
    }

    private String userAgent() {
        String contact = properties.geocoding().contactEmail();
        return contact.isBlank()
                ? "wander/" + properties.version() + " (self-hosted travel planner)"
                : "wander/" + properties.version() + " (" + contact + ")";
    }

    @Override
    public PlaceFacts fetch(String osmRef, String language) {
        JsonNode tags = osmTags(osmRef);
        String wikidataId = text(tags, "wikidata");
        String openingHours = text(tags, "opening_hours");
        String website = firstText(tags, "website", "contact:website", "url");
        String phone = firstText(tags, "phone", "contact:phone");

        // Most places stop here, and that is a complete answer rather than a
        // failure: OpenStreetMap knows the hours of a great many things it has
        // never had an encyclopaedia article about.
        if (wikidataId == null) {
            return new PlaceFacts(null, null, null, null, null, openingHours, website, phone,
                    List.of());
        }

        JsonNode entity = wikidataEntity(wikidataId);
        String articleTitle = sitelinkTitle(entity, language);
        String imageFile = claimValue(entity, "P18");

        Summary summary = articleTitle == null
                ? new Summary(null, null, null)
                : wikipediaSummary(language, articleTitle);

        List<PlaceFacts.PhotoCandidate> photos = imageFile == null
                ? List.of()
                : commonsCandidates(imageFile);

        return new PlaceFacts(wikidataId, summary.title(), summary.extract(), summary.url(),
                summary.extract() == null ? null : WIKIPEDIA_LICENCE,
                openingHours, website, phone, photos);
    }

    /** `/lookup` with extratags, which is where `wikidata` and `opening_hours` live. */
    private JsonNode osmTags(String osmRef) {
        String id = nominatimId(osmRef);
        if (id == null) {
            return null;
        }
        nominatimGate.pass();
        JsonNode root = get(nominatim, uri -> uri.path("/lookup")
                .queryParam("osm_ids", id)
                .queryParam("format", "jsonv2")
                .queryParam("extratags", 1)
                .build(), "OpenStreetMap lookup");
        if (root == null || !root.isArray() || root.isEmpty()) {
            return null;
        }
        return root.get(0).path("extratags");
    }

    /**
     * `way/34633854` becomes `W34633854`, which is the form `/lookup` wants.
     * Anything else is refused rather than guessed at.
     */
    static String nominatimId(String osmRef) {
        if (osmRef == null || !osmRef.contains("/")) {
            return null;
        }
        String[] parts = osmRef.split("/", 2);
        String prefix = switch (parts[0].toLowerCase()) {
            case "node" -> "N";
            case "way" -> "W";
            case "relation" -> "R";
            default -> null;
        };
        if (prefix == null || parts[1].isBlank() || !parts[1].chars().allMatch(Character::isDigit)) {
            return null;
        }
        return prefix + parts[1];
    }

    private JsonNode wikidataEntity(String wikidataId) {
        wikimediaGate.pass();
        JsonNode root = get(wikidata, uri -> uri.path("/wiki/Special:EntityData/" + wikidataId + ".json")
                .build(), "Wikidata entity");
        return root == null ? null : root.path("entities").path(wikidataId);
    }

    /** The article title on the caller's Wikipedia, falling back to English. */
    static String sitelinkTitle(JsonNode entity, String language) {
        if (entity == null || entity.isMissingNode()) {
            return null;
        }
        JsonNode sitelinks = entity.path("sitelinks");
        for (String candidate : new String[] { language + "wiki", "enwiki" }) {
            JsonNode link = sitelinks.path(candidate);
            if (link.hasNonNull("title")) {
                return link.get("title").asString();
            }
        }
        return null;
    }

    /** The first value of a Wikidata claim — P18 is "image". */
    static String claimValue(JsonNode entity, String property) {
        if (entity == null || entity.isMissingNode()) {
            return null;
        }
        JsonNode claims = entity.path("claims").path(property);
        if (!claims.isArray() || claims.isEmpty()) {
            return null;
        }
        JsonNode value = claims.get(0).path("mainsnak").path("datavalue").path("value");
        return value.isString() ? value.asString() : null;
    }

    private record Summary(String title, String extract, String url) {
    }

    private Summary wikipediaSummary(String language, String title) {
        wikimediaGate.pass();
        RestClient wikipedia = client("https://" + language + ".wikipedia.org");
        JsonNode root = get(wikipedia,
                uri -> uri.path("/api/rest_v1/page/summary/" + encode(title)).build(),
                "Wikipedia summary");
        return parseSummary(root);
    }

    static Summary parseSummary(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return new Summary(null, null, null);
        }
        String extract = root.hasNonNull("extract") ? root.get("extract").asString() : null;
        String title = root.hasNonNull("title") ? root.get("title").asString() : null;
        String url = root.path("content_urls").path("desktop").path("page").isString()
                ? root.path("content_urls").path("desktop").path("page").asString()
                : null;
        return new Summary(title, blankToNull(extract), url);
    }

    /**
     * The image, and its credit, from Commons.
     *
     * `extmetadata` is where the author and licence live, and its shape varies —
     * `Artist` is often HTML, `LicenseShortName` is sometimes absent when
     * `License` is present. A candidate whose credit cannot be read is dropped:
     * an unattributed picture is one this instance has no right to show.
     */
    private List<PlaceFacts.PhotoCandidate> commonsCandidates(String fileName) {
        wikimediaGate.pass();
        JsonNode root = get(commons, uri -> uri.path("/w/api.php")
                .queryParam("action", "query")
                .queryParam("format", "json")
                .queryParam("prop", "imageinfo")
                .queryParam("iiprop", "url|extmetadata")
                .queryParam("iiurlwidth", 640)
                .queryParam("titles", "File:" + fileName)
                .build(), "Commons image info");
        return parseCandidates(root, config.photoCount());
    }

    static List<PlaceFacts.PhotoCandidate> parseCandidates(JsonNode root, int limit) {
        List<PlaceFacts.PhotoCandidate> found = new ArrayList<>();
        if (root == null || root.isMissingNode()) {
            return found;
        }
        JsonNode pages = root.path("query").path("pages");
        if (!pages.isObject()) {
            return found;
        }
        // Jackson 3's propertyNames() is a Collection, not an Iterator.
        for (String name : pages.propertyNames()) {
            JsonNode info = pages.path(name).path("imageinfo");
            if (!info.isArray()) {
                continue;
            }
            for (JsonNode image : info) {
                if (found.size() >= limit) {
                    return found;
                }
                JsonNode meta = image.path("extmetadata");
                String author = plainText(metaValue(meta, "Artist"));
                String licence = metaValue(meta, "LicenseShortName");
                if (licence == null) {
                    licence = metaValue(meta, "License");
                }
                PlaceFacts.PhotoCandidate candidate = new PlaceFacts.PhotoCandidate(
                        textOrNull(image, "url"),
                        textOrNull(image, "thumburl"),
                        author,
                        licence,
                        textOrNull(image, "descriptionurl"));
                if (candidate.isUsable()) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    private static String metaValue(JsonNode meta, String key) {
        JsonNode node = meta.path(key).path("value");
        return node.isString() ? blankToNull(node.asString()) : null;
    }

    /** Commons' `Artist` is frequently a link. The name is what belongs in a caption. */
    static String plainText(String html) {
        if (html == null) {
            return null;
        }
        String stripped = html.replaceAll("<[^>]*>", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return blankToNull(stripped);
    }

    private JsonNode get(RestClient client, java.util.function.Function<UriBuilder, URI> uri,
            String what) {
        try {
            String body = client.get().uri(uri).retrieve().body(String.class);
            return body == null || body.isBlank() ? null : json.readTree(body);
        } catch (RuntimeException ex) {
            // An enrichment is a nicety. A service being down costs a description,
            // not somebody's itinerary.
            log.debug("{} failed: {}", what, ex.toString());
            return null;
        }
    }

    private static String encode(String title) {
        return java.net.URLEncoder.encode(title.replace(' ', '_'),
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "_");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isString() ? blankToNull(value.asString()) : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? blankToNull(value.asString()) : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Exposed for the parser tests, which is the whole of the risk in this class. */
    static Optional<String> licenceOf(JsonNode meta) {
        return Optional.ofNullable(metaValue(meta, "LicenseShortName"));
    }
}
