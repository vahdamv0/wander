package com.wander.geo;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.wander.common.FeatureDisabledException;
import com.wander.config.WanderProperties;
import com.wander.geo.dto.PlaceSuggestion;

/**
 * Everything between "the user typed three letters" and an outbound request: the
 * feature toggle, query hygiene, a cache, and the rate gate.
 *
 * The cache is what makes the one-request-a-second limit livable — a typeahead
 * repeats prefixes constantly, and two people planning the same city ask the
 * same questions. It is an in-memory LRU on purpose: place search is a
 * convenience, so losing it on restart costs nothing, and a Redis dependency
 * would spoil "one container, one database".
 */
@Service
public class GeocodingService {

    /** Below this a query matches half the planet and the ranking is noise. */
    private static final int MIN_QUERY_LENGTH = 3;
    /**
     * Cap on the language list. It is caller-supplied and part of the cache key,
     * so an unbounded one is a way to fill the cache with junk.
     */
    private static final int MAX_LANGUAGE_LENGTH = 48;
    private static final int MAX_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 5;

    private final GeocoderClient geocoder;
    private final WanderProperties.Geocoding config;
    private final RateGate gate;
    private final Map<String, Entry> cache;
    private final Duration ttl;

    private record Entry(List<PlaceSuggestion> suggestions, Instant storedAt) {
    }

    public GeocodingService(GeocoderClient geocoder, WanderProperties properties) {
        this.geocoder = geocoder;
        this.config = properties.geocoding();
        this.gate = new RateGate(config.minIntervalMillis(), config.maxWaitMillis());
        this.ttl = Duration.ofSeconds(config.cacheSeconds());
        int capacity = Math.max(1, config.cacheSize());
        // Access-ordered, so the entry evicted is the one nobody has asked for
        // in the longest time rather than merely the oldest.
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, Entry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > capacity;
            }
        });
    }

    /** True when this instance searches at all — the client hides its box otherwise. */
    public boolean isEnabled() {
        return config.enabled();
    }

    public List<PlaceSuggestion> search(String query, Integer limit, String language) {
        if (!config.enabled()) {
            throw new FeatureDisabledException("Place search is turned off on this instance");
        }
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "A place search needs at least " + MIN_QUERY_LENGTH + " characters");
        }
        int capped = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        // Case and spacing are not part of the question, so they should not split
        // the cache. The limit is, though: a cached 5 cannot answer a 10 — and so
        // is the language, or the first Japanese-speaking caller would hand
        // Japanese names to everyone after them.
        String requested = language(language);
        String key = normalize(trimmed) + " " + capped + " " + requested;
        List<PlaceSuggestion> cached = fromCache(key);
        if (cached != null) {
            return cached;
        }

        gate.pass();
        // Between the miss above and here another caller may have filled the
        // entry. Checking again turns a duplicate outbound request into a hit,
        // which is worth it when the budget is one request a second.
        List<PlaceSuggestion> raced = fromCache(key);
        if (raced != null) {
            return raced;
        }

        List<PlaceSuggestion> found = geocoder.search(trimmed, capped, requested);
        cache.put(key, new Entry(found, Instant.now()));
        return found;
    }

    /**
     * The caller's language list, or the instance default. A geocoder given no
     * preference answers in the place's own language, which is why this is never
     * left empty: "Kyoto" comes back as 京都.
     */
    private String language(String requested) {
        String header = requested == null ? "" : requested.trim();
        // Only what a language list may contain, so nothing caller-supplied
        // reaches the outbound URL or the cache key unfiltered.
        String cleaned = header.replaceAll("[^A-Za-z0-9,;=.*\\-]", "");
        if (cleaned.length() > MAX_LANGUAGE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LANGUAGE_LENGTH);
        }
        return cleaned.isBlank() ? config.language() : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String query) {
        return query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private List<PlaceSuggestion> fromCache(String key) {
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (Duration.between(entry.storedAt(), Instant.now()).compareTo(ttl) > 0) {
            cache.remove(key);
            return null;
        }
        return entry.suggestions();
    }
}
