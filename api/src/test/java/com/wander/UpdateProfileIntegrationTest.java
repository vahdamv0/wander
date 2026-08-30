package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Renaming yourself.
 *
 * The assertion that matters is the third one: the principal is written into the
 * session at sign-in and read back on every request, so a change that only
 * touched the row would be undone — visibly — by the next page load.
 */
class UpdateProfileIntegrationTest extends IntegrationTestBase {

    private static String body(String displayName) {
        return """
                {"displayName":"%s"}
                """.formatted(displayName);
    }

    @Test
    void theNameIsReturnedAndSurvivesInTheSession() {
        Session session = register("rename");

        ResponseEntity<String> updated = put(session, "/api/auth/profile", body("Renamed Person"));
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody()).contains("Renamed Person");

        // The session is the part that would rot: /me answers from the principal
        // stored in it, not from the database.
        ResponseEntity<String> me = get(session, "/api/auth/me");
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody()).contains("Renamed Person");
    }

    @Test
    void theNameSurvivesSigningInAgain() {
        Session session = register("rename-relogin");
        assertThat(put(session, "/api/auth/profile", body("Second Name")).getStatusCode().value()).isEqualTo(200);

        Session fresh = login(session.email(), "correct-horse-battery");

        assertThat(get(fresh, "/api/auth/me").getBody()).contains("Second Name");
    }

    @Test
    void aBlankNameIsRefused() {
        Session session = register("rename-blank");

        assertThat(put(session, "/api/auth/profile", body("   ")).getStatusCode().value()).isEqualTo(400);
        assertThat(get(session, "/api/auth/me").getBody()).contains("rename-blank");
    }

    @Test
    void surroundingSpaceIsTrimmed() {
        Session session = register("rename-trim");

        ResponseEntity<String> updated = put(session, "/api/auth/profile", body("  Spaced Out  "));

        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody()).contains("\"displayName\":\"Spaced Out\"");
    }

    @Test
    void anonymousCallersAreRefused() {
        ResponseEntity<String> response = http().put()
                .uri("/api/auth/profile")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body("Nobody"))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }
}
