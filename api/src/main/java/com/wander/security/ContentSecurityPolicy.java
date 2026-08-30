package com.wander.security;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.wander.config.WanderProperties;

/**
 * The Content-Security-Policy header, built from this instance's configuration.
 *
 * Spring Security's defaults already give `nosniff` and `X-Frame-Options: DENY`,
 * and Caddy adds HSTS once it is serving TLS. A CSP is the one that was missing,
 * and it is the one that matters most here: wander draws a map, its glyphs and
 * its sprites from a third party, and shows photographs from Wikimedia Commons,
 * so an injected script has plenty of places to try to phone home from.
 *
 * **Assembled rather than hardcoded, because the hosts are an operator's
 * choice.** `WANDER_MAP_STYLE_URL` and its neighbours exist precisely so a
 * self-hoster can point at their own tile server, and a fixed policy would make
 * the first person who does that stare at a blank map. So the origins come out
 * of {@code MapTiles} at startup and follow whatever it is set to.
 *
 * Two directives are looser than they look, and both for reasons that are not
 * going away:
 *
 *  - `style-src` allows `'unsafe-inline'`. Leaflet and MapLibre both build DOM
 *    outside any Angular template and position it by writing `style` attributes
 *    onto the nodes as they go — that is what a map *is*. Nonces cannot reach
 *    attributes written by a library, so this is the honest setting rather than
 *    one that pretends.
 *  - `img-src` allows `data:` and `blob:`. Map sprites and the marker canvases
 *    arrive that way.
 *
 * `script-src` gets neither, which is the half that actually stops an injection,
 * and `object-src 'none'` with `base-uri 'self'` closes the two classic ways
 * around a script directive.
 */
@Component
public class ContentSecurityPolicy {

    /**
     * Scheme and authority, stopping before the path — which is what a CSP
     * source is. Written as a regex rather than parsed as a URI because
     * {@code tileUrl} carries `{z}/{x}/{y}` placeholders that are not legal in
     * one, and this only ever needs the part in front of them.
     */
    private static final Pattern ORIGIN = Pattern.compile("^(https?://[^/?#]+)");

    /**
     * Where a Commons photograph actually lives. The enrichment offers URLs on
     * this host and {@code Place.setPhoto} stores them, so it is not an
     * operator's choice the way the map is — it is where the feature's own data
     * comes from.
     */
    private static final String COMMONS_MEDIA = "https://upload.wikimedia.org";

    private final String policy;
    private final boolean enabled;
    private final boolean reportOnly;

    public ContentSecurityPolicy(WanderProperties properties) {
        this.enabled = properties.csp().enabled();
        this.reportOnly = properties.csp().reportOnly();
        this.policy = build(properties);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when the header should be the report-only one. */
    public boolean isReportOnly() {
        return reportOnly;
    }

    public String header() {
        return policy;
    }

    private static String build(WanderProperties properties) {
        WanderProperties.MapTiles map = properties.map();

        // A LinkedHashSet so the header is stable between restarts: a policy
        // whose directives shuffle is one nobody can diff.
        Set<String> mapOrigins = new LinkedHashSet<>();
        addOrigin(mapOrigins, map.styleUrl());
        addOrigin(mapOrigins, map.darkStyleUrl());
        addOrigin(mapOrigins, map.tileUrl());

        Set<String> extra = new LinkedHashSet<>();
        for (String source : properties.csp().extraSources().trim().split("\\s+")) {
            if (!source.isBlank()) {
                extra.add(source);
            }
        }

        String mapSources = join(mapOrigins);
        String extraSources = join(extra);

        return String.join("; ",
                "default-src 'self'",
                "base-uri 'self'",
                "object-src 'none'",
                "frame-ancestors 'none'",
                "form-action 'self'",
                "script-src 'self'",
                // See the class note: a map library writes inline styles.
                "style-src 'self' 'unsafe-inline'",
                ("img-src 'self' data: blob: " + COMMONS_MEDIA + mapSources + extraSources).trim(),
                ("font-src 'self' data:" + mapSources + extraSources).trim(),
                // Place search, enrichment and the forecast are all proxied
                // through this origin, so the only third party the browser
                // itself talks to is the basemap.
                ("connect-src 'self'" + mapSources + extraSources).trim(),
                // MapLibre's tile-parsing worker, copied to this origin by
                // angular.json. blob: because the library may wrap it.
                "worker-src 'self' blob:",
                "manifest-src 'self'");
    }

    private static void addOrigin(Set<String> into, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        Matcher matcher = ORIGIN.matcher(url.trim());
        if (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    private static String join(Set<String> sources) {
        return sources.isEmpty() ? "" : " " + String.join(" ", sources);
    }
}
