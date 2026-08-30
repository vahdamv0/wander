package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Changing your own password — the only way one moves on this instance, since
 * nothing here sends mail and there is therefore no reset.
 *
 * The interesting assertions are not the happy path. They are that the old
 * password stops working, that a wrong current password is a 400 rather than the
 * 401 the client reads as "your session has gone", and that other sessions for
 * the account are ended while the one doing the changing is not.
 */
class ChangePasswordIntegrationTest extends IntegrationTestBase {

    private static final String ORIGINAL = "correct-horse-battery";
    private static final String REPLACEMENT = "a-quite-different-passphrase";

    private static String body(String current, String next) {
        return """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(current, next);
    }

    @Test
    void theNewPasswordWorksAndTheOldOneStopsWorking() {
        Session session = register("pwchange");

        ResponseEntity<String> changed = post(session, "/api/auth/password", body(ORIGINAL, REPLACEMENT));
        assertThat(changed.getStatusCode().value()).isEqualTo(204);

        assertThat(attemptLogin(session.email(), ORIGINAL).getStatusCode().value())
                .as("the old password after a change")
                .isEqualTo(401);
        assertThat(attemptLogin(session.email(), REPLACEMENT).getStatusCode().value())
                .as("the new password")
                .isEqualTo(200);
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndChangesNothing() {
        Session session = register("pwwrong");

        ResponseEntity<String> refused = post(session, "/api/auth/password", body("not-the-password", REPLACEMENT));

        // 400 and not 401 on purpose: the client treats a 401 as the session
        // having expired, so answering one here would sign somebody out over a
        // typo.
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(attemptLogin(session.email(), ORIGINAL).getStatusCode().value())
                .as("the original password still works")
                .isEqualTo(200);
    }

    @Test
    void reusingTheCurrentPasswordIsRefused() {
        Session session = register("pwsame");

        ResponseEntity<String> refused = post(session, "/api/auth/password", body(ORIGINAL, ORIGINAL));

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aShortNewPasswordIsRejectedByValidation() {
        Session session = register("pwshort");

        ResponseEntity<String> refused = post(session, "/api/auth/password", body(ORIGINAL, "short"));

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(attemptLogin(session.email(), ORIGINAL).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void anonymousCallersAreRefused() {
        // Not a @PublicEndpoint, so default-deny applies. EndpointAuthRatchetTest
        // covers this class of thing generally; it is asserted here too because
        // this endpoint changes a credential.
        ResponseEntity<String> response = http().post()
                .uri("/api/auth/password")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body(ORIGINAL, REPLACEMENT))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void otherSessionsAreEndedButTheOneMakingTheChangeSurvives() {
        Session first = register("pwsessions");
        // The same account, signed in from somewhere else — a second browser, or
        // the phone somebody left at a café.
        Session second = login(first.email(), ORIGINAL);

        assertThat(get(second, "/api/auth/me").getStatusCode().value())
                .as("the second session before the change")
                .isEqualTo(200);

        assertThat(post(first, "/api/auth/password", body(ORIGINAL, REPLACEMENT)).getStatusCode().value())
                .isEqualTo(204);

        assertThat(get(second, "/api/auth/me").getStatusCode().value())
                .as("the other session after the change")
                .isEqualTo(401);
        assertThat(get(first, "/api/auth/me").getStatusCode().value())
                .as("the session that made the change")
                .isEqualTo(200);
    }
}
