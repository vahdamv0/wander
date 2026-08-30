package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The Content-Security-Policy, and the fact that it follows the map settings.
 *
 * The interesting property is not that a header exists — it is that the origins
 * inside it came from this instance's configuration. A hardcoded policy would
 * pass a "header is present" test perfectly and then blank the map for the first
 * self-hoster who points at their own tile server, which is the failure this
 * project has already had once with the MapLibre worker: everything returns 200
 * and nothing draws.
 */
@TestPropertySource(properties = {
        "wander.map.style-url=https://tiles.example.org/styles/liberty",
        "wander.map.dark-style-url=https://tiles.example.org/styles/dark",
        "wander.map.tile-url=https://raster.example.net/{z}/{x}/{y}.png" })
class ContentSecurityPolicyIntegrationTest extends IntegrationTestBase {

    /** The SPA shell, which is the response a browser applies the policy to. */
    private ResponseEntity<Void> shell() {
        return http().get().uri("/index.html").retrieve().toBodilessEntity();
    }

    @Test
    void thePolicyCarriesTheConfiguredMapOriginsAndNothingElse() {
        String policy = shell().getHeaders().getFirst("Content-Security-Policy");
        assertThat(policy).isNotNull();

        assertThat(policy).contains("connect-src 'self' https://tiles.example.org https://raster.example.net");
        // The raster URL's {z}/{x}/{y} is not a legal URI, which is why the
        // origin is taken with a regex rather than parsed — if that ever
        // regresses, this host goes missing and the map stops loading tiles.
        assertThat(policy).contains("https://raster.example.net");
        // The default hosts must not be smuggled in beside the configured ones.
        assertThat(policy).doesNotContain("openfreemap");
    }

    @Test
    void theDirectivesThatActuallyStopAnInjectionAreStrict() {
        String policy = shell().getHeaders().getFirst("Content-Security-Policy");

        assertThat(policy).contains("script-src 'self'");
        assertThat(policy).contains("object-src 'none'");
        assertThat(policy).contains("base-uri 'self'");
        assertThat(policy).contains("frame-ancestors 'none'");
        // script-src must never pick up the exemption style-src needs for
        // Leaflet's and MapLibre's inline attributes.
        assertThat(policy).doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    void theMapLibreWorkerAndCommonsPhotographsAreAllowedFor() {
        String policy = shell().getHeaders().getFirst("Content-Security-Policy");

        assertThat(policy).contains("worker-src 'self' blob:");
        assertThat(policy).contains("https://upload.wikimedia.org");
    }

    /**
     * Spring Security's own defaults have to survive the headers block being
     * customised — it is easy to replace them rather than add to them.
     */
    @Test
    void theDefaultHeadersAreStillThere() {
        var headers = shell().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
    }
}
