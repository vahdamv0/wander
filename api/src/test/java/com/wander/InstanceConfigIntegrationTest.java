package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The settings the client reads before it draws a map or a search box. Nothing
 * here is secret, but nothing anonymous needs it either — see
 * {@link EndpointAuthRatchetTest}, which is what actually holds that line.
 */
class InstanceConfigIntegrationTest extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void theClientIsToldWhereTilesComeFrom() {
        Session user = register("vera");

        var response = get(user, "/api/config");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> config = asMap(response.getBody());
        assertThat(config).containsEntry("searchEnabled", true);

        Map<String, Object> map = (Map<String, Object>) config.get("map");
        assertThat(map).containsEntry("enabled", true);
        // A Leaflet URL template. Compiling this into the client would stop an
        // operator pointing their instance at their own tile server.
        assertThat((String) map.get("tileUrl")).contains("{z}").contains("{x}").contains("{y}");
        // The tile service's terms require this to stay visible, so it travels
        // with the URL rather than being hardcoded beside the map.
        assertThat((String) map.get("attribution")).isNotBlank();
        assertThat(map.get("maxZoom")).isEqualTo(19);
    }

    @Test
    void whetherSignUpsAreAcceptedIsReadableBeforeSigningIn() {
        // Deliberately public: the login page has to know this before anybody has
        // a session, which is exactly why it used to get it wrong.
        var response = http().get().uri("/api/config/sign-in").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(response.getBody())).containsEntry("registrationEnabled", true);
    }

    @Test
    void configIsNotReadableAnonymously() {
        var response = http().get().uri("/api/config").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
