package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * An instance that does not accept sign-ups.
 *
 * Its own context, because the switch is read at configuration time. Two halves
 * that have to agree, and until now neither was tested: the server refuses, and
 * the client is told not to offer.
 */
@TestPropertySource(properties = "wander.registration-enabled=false")
class RegistrationDisabledIntegrationTest extends IntegrationTestBase {

    @Test
    void registeringIsRefused() {
        String csrf = bootstrapCsrf();

        var response = http().post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("""
                        {"email":"uninvited-%s@example.com","displayName":"Uninvited",
                         "password":"correct-horse-battery"}
                        """.formatted(unique()))
                .retrieve()
                .toEntity(String.class);

        // Fail closed: the account is not quietly created anyway.
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void theSignInPageIsToldNotToOfferIt() {
        var response = http().get().uri("/api/config/sign-in").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(response.getBody())).containsEntry("registrationEnabled", false);
    }
}
