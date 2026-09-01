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

    /**
     * The source link, on both endpoints, and it is the licence that makes this
     * worth a test rather than the feature.
     *
     * wander is AGPL-3.0-or-later and section 13 owes source to everybody
     * offered the instance over a network. The *anonymous* half is the half that
     * matters: on a public instance most people reach the sign-in page and stop,
     * so a link that existed only behind authentication would miss the audience
     * the clause is written for. Nothing else in the suite would notice it going
     * missing — the app works perfectly without it.
     */
    @Test
    void theSourceIsOfferedToAnybodyTheInstanceIsOfferedTo() {
        var anonymous = http().get().uri("/api/config/sign-in").retrieve().toEntity(String.class);
        assertThat((String) asMap(anonymous.getBody()).get("sourceUrl")).startsWith("http");

        Session user = register("linus");
        assertThat((String) asMap(get(user, "/api/config").getBody()).get("sourceUrl"))
                .startsWith("http");
    }

    /**
     * Zero on an instance with the demo off, which is what stops the trips page
     * promising a deletion schedule to somebody whose trips nothing deletes.
     * `DemoSeedIntegrationTest` has the other side, where it is a real interval.
     */
    @Test
    void thereIsNoSweepScheduleToAnnounceWithoutTheDemo() {
        assertThat(asMap(get(register("ada"), "/api/config").getBody()))
                .containsEntry("demoSweepMinutes", 0);
    }

    @Test
    void configIsNotReadableAnonymously() {
        var response = http().get().uri("/api/config").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
