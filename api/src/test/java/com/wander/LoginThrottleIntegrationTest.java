package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Guessing passwords at `/api/auth/login`, over HTTP.
 *
 * The counter itself is covered by LoginThrottleTest; what this holds is the
 * wiring — that the throttle is actually in front of the endpoint, that it
 * answers 429 rather than 401, and that it lets go the moment somebody gets
 * their password right.
 *
 * Its own context, and that is not incidental: the throttle is one bean holding
 * one map, every test in this suite arrives from 127.0.0.1, and a test that
 * deliberately exhausts a counter would take the rest of the suite's sign-ins
 * down with it. `@TestPropertySource` gives this class a context, and therefore
 * a throttle, of its own. The per-address limit is left high here for the same
 * reason — this class's own tests share an address.
 */
@TestPropertySource(properties = {
        "wander.login.max-failures-per-email=3",
        "wander.login.max-failures-per-address=1000"
})
class LoginThrottleIntegrationTest extends IntegrationTestBase {

    private static final String RIGHT = "correct-horse-battery";
    private static final String WRONG = "not-the-password";

    @Test
    void afterEnoughWrongPasswordsEvenTheRightOneIsRefused() {
        Session account = register("throttled");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(attemptLogin(account.email(), WRONG).getStatusCode().value())
                    .as("attempt %d is an ordinary failure", attempt + 1)
                    .isEqualTo(401);
        }

        // The gate is in front of the password check, not behind it: the point is
        // to stop spending a bcrypt round per guess, so the correct password gets
        // the same answer as another wrong one.
        assertThat(attemptLogin(account.email(), RIGHT).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void gettingItRightClearsTheCount() {
        Session account = register("recovering");

        assertThat(attemptLogin(account.email(), WRONG).getStatusCode().value()).isEqualTo(401);
        assertThat(attemptLogin(account.email(), WRONG).getStatusCode().value()).isEqualTo(401);
        assertThat(attemptLogin(account.email(), RIGHT).getStatusCode().value()).isEqualTo(200);

        // Two fumbles and a success is a normal morning and must leave nothing
        // behind — otherwise enough normal mornings add up to a locked account.
        assertThat(attemptLogin(account.email(), WRONG).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void anAddressThatDoesNotExistIsThrottledLikeOneThatDoes() {
        String nobody = "nobody-%s@example.com".formatted(unique());

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(attemptLogin(nobody, WRONG).getStatusCode().value()).isEqualTo(401);
        }

        // Anything else here would be an oracle: "still 401 after four tries"
        // versus "429" would say which addresses have accounts, which is exactly
        // what hideUserNotFoundExceptions exists to keep quiet about.
        assertThat(attemptLogin(nobody, WRONG).getStatusCode().value()).isEqualTo(429);
    }
}
