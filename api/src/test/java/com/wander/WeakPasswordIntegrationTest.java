package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The password policy over HTTP, at both doors.
 *
 * {@code GuessablePasswordValidatorTest} covers which passwords are refused;
 * this covers that the refusal is actually wired to the two endpoints that set
 * one. A policy applied at registration and not at change-password would just be
 * the door people walked through to get a weak password.
 */
class WeakPasswordIntegrationTest extends IntegrationTestBase {

    private ResponseEntity<String> registerWith(String password) {
        String csrf = bootstrapCsrf();
        return http().post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("""
                        {"email":"weak-%s@example.com","displayName":"Weak","password":"%s"}
                        """.formatted(unique(), password))
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    void aGuessablePasswordIsRefusedAtRegistration() {
        // Ten characters, so the length bound is satisfied and this is the new
        // rule talking rather than the old one.
        assertThat(registerWith("password12").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aStraightRunOfDigitsIsRefusedTooEvenThoughItIsLongEnough() {
        assertThat(registerWith("1234567890").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aRealPassphraseIsStillAccepted() {
        assertThat(registerWith("a-quiet-week-in-kanazawa").getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void changingToAGuessablePasswordIsRefusedAsWell() {
        Session user = register("rowan");

        ResponseEntity<String> response = post(user, "/api/auth/password", """
                {"currentPassword":"correct-horse-battery","newPassword":"qwerty12345"}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        // And the old password still works, so nothing was half-applied.
        assertThat(attemptLogin(user.email(), "correct-horse-battery").getStatusCode().value())
                .isEqualTo(200);
    }
}
