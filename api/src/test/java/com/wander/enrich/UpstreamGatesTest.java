package com.wander.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wander.PostgresIntegrationTest;
import com.wander.geo.GeocodingService;
import com.wander.geo.RateGate;

/**
 * Place search and enrichment's tag lookup must queue behind the *same* gate.
 *
 * Nominatim's policy is one request a second **from a client**, and it is the
 * instance that gets blocked for breaking it. Two gates would silently make that
 * two a second — an infringement with no symptom until somebody's instance is
 * refused service, which is the worst possible way to find out. Hence a test for
 * what is otherwise an invisible wiring fact.
 */
@PostgresIntegrationTest
class UpstreamGatesTest {

    @Autowired
    private RateGate nominatimGate;

    @Autowired
    private GeocodingService geocoding;

    @Autowired
    private WikiEnrichmentClient enrichmentClient;

    @Test
    void thereIsExactlyOneNominatimGateAndBothCallersHoldIt() {
        assertThat(nominatimGate).as("the shared gate is a bean").isNotNull();
        assertThat(fieldOf(geocoding, "gate"))
                .as("place search holds the shared gate")
                .isSameAs(nominatimGate);
        assertThat(fieldOf(enrichmentClient, "nominatimGate"))
                .as("the enrichment lookup holds the same one, not a second")
                .isSameAs(nominatimGate);
    }

    @Test
    void wikimediaHasItsOwnGateRatherThanSharingNominatims() {
        // Separate services with their own, far more generous limits: an
        // enrichment should not wait behind somebody's typeahead.
        assertThat(fieldOf(enrichmentClient, "wikimediaGate")).isNotSameAs(nominatimGate);
    }

    private static Object fieldOf(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("no field " + name + " on " + target.getClass(), ex);
        }
    }
}
