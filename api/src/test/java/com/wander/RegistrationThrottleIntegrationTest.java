package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Creating accounts is metered, per address.
 *
 * The endpoint is anonymous, spends a bcrypt round on every call and leaves a
 * row behind when it works — so without this it is both a way to burn the box's
 * CPU and a way to fill its user table, and both get considerably more
 * interesting the moment self-signup is switched on.
 *
 * **Its own context, on purpose**, exactly as {@code LoginThrottleIntegrationTest}
 * has one. There is a single counter bean and every test in the suite arrives
 * from 127.0.0.1, so exhausting the registration counter inside the shared
 * context would stop every other test creating its accounts. The test profile
 * sets the limit absurdly high for that reason; this class sets it back down to
 * something a test can actually reach.
 */
@TestPropertySource(properties = {
        "wander.registration-enabled=true",
        "wander.login.max-registrations-per-address=3",
        "wander.login.window-minutes=15" })
class RegistrationThrottleIntegrationTest extends IntegrationTestBase {

    private ResponseEntity<String> attemptRegister() {
        String csrf = bootstrapCsrf();
        return http().post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("""
                        {"email":"crowd-%s@example.com","displayName":"Crowd","password":"correct-horse-battery"}
                        """.formatted(unique()))
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    void aBurstOfSignUpsFromOneAddressIsRefusedAfterTheLimit() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(attemptRegister().getStatusCode().value())
                    .as("registration %d, inside the limit", attempt + 1)
                    .isEqualTo(201);
        }

        ResponseEntity<String> refused = attemptRegister();
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        // The message says to wait rather than saying what the limit is: the
        // number is not the caller's business and knowing it only helps somebody
        // pace themselves under it.
        assertThat(refused.getBody()).contains("Wait a few minutes");
    }

    /**
     * A *refused* registration still counts. It cost the same lookup a
     * successful one did, and a counter that only remembered the ones that
     * worked would let somebody who is being refused keep asking for free.
     */
    @Test
    void aRefusedSignUpStillCountsAgainstTheLimit() {
        String csrf = bootstrapCsrf();
        String taken = "taken-%s@example.com".formatted(unique());
        String body = """
                {"email":"%s","displayName":"Taken","password":"correct-horse-battery"}
                """.formatted(taken);

        // The first takes the address; the next two are 409s that still count.
        for (int attempt = 0; attempt < 3; attempt++) {
            http().post().uri("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                    .header("X-XSRF-TOKEN", csrf)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }

        assertThat(attemptRegister().getStatusCode().value()).isEqualTo(429);
    }
}
