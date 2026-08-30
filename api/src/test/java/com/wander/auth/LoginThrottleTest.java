package com.wander.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.wander.common.RateLimitedException;

/**
 * The counter behind the login throttle, tested here rather than over HTTP
 * because the interesting cases are about *time* and about which key ran out —
 * neither of which an integration test can provoke without waiting for real
 * minutes to pass.
 */
class LoginThrottleTest {

    private static final String ADDRESS = "203.0.113.7";

    private LoginThrottle throttle(int perEmail, int perAddress, Duration window) {
        return new LoginThrottle(perEmail, perAddress, 100, window, 100);
    }

    @Test
    void lettingSomebodyTryUntilTheLimitAndThenRefusing() {
        LoginThrottle throttle = throttle(3, 100, Duration.ofMinutes(15));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatCode(() -> throttle.check("ana@example.com", ADDRESS)).doesNotThrowAnyException();
            throttle.failed("ana@example.com", ADDRESS);
        }

        assertThatThrownBy(() -> throttle.check("ana@example.com", ADDRESS))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void aSuccessClearsTheCount() {
        LoginThrottle throttle = throttle(3, 100, Duration.ofMinutes(15));
        throttle.failed("ana@example.com", ADDRESS);
        throttle.failed("ana@example.com", ADDRESS);

        // Typing it wrong twice and then right is an ordinary morning, and must
        // leave nothing behind — otherwise a week of them adds up to a lockout.
        throttle.succeeded("ana@example.com", ADDRESS);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatCode(() -> throttle.check("ana@example.com", ADDRESS)).doesNotThrowAnyException();
            throttle.failed("ana@example.com", ADDRESS);
        }
        assertThatThrownBy(() -> throttle.check("ana@example.com", ADDRESS))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void theWindowExpires() throws InterruptedException {
        LoginThrottle throttle = throttle(1, 100, Duration.ofMillis(50));
        throttle.failed("ana@example.com", ADDRESS);
        assertThatThrownBy(() -> throttle.check("ana@example.com", ADDRESS))
                .isInstanceOf(RateLimitedException.class);

        Thread.sleep(80);

        // Not a lockout: with no password reset on this instance, one that
        // outlived its window would be a way to keep the real owner out for good.
        assertThatCode(() -> throttle.check("ana@example.com", ADDRESS)).doesNotThrowAnyException();
    }

    @Test
    void oneAccountRunningOutDoesNotStopAnother() {
        LoginThrottle throttle = throttle(1, 100, Duration.ofMinutes(15));
        throttle.failed("ana@example.com", ADDRESS);

        assertThatCode(() -> throttle.check("bruno@example.com", ADDRESS)).doesNotThrowAnyException();
    }

    @Test
    void sprayingManyAccountsFromOneAddressRunsOutToo() {
        LoginThrottle throttle = throttle(10, 3, Duration.ofMinutes(15));

        // No single account gets near its own limit, which is exactly the attack
        // the per-email counter cannot see.
        throttle.failed("one@example.com", ADDRESS);
        throttle.failed("two@example.com", ADDRESS);
        throttle.failed("three@example.com", ADDRESS);

        assertThatThrownBy(() -> throttle.check("four@example.com", ADDRESS))
                .isInstanceOf(RateLimitedException.class);
        // Somebody else's connection is untouched.
        assertThatCode(() -> throttle.check("four@example.com", "198.51.100.4")).doesNotThrowAnyException();
    }

    @Test
    void theCaseOfTheAddressIsNotAWayToStartAgain() {
        LoginThrottle throttle = throttle(1, 100, Duration.ofMinutes(15));
        throttle.failed("Ana@Example.com", ADDRESS);

        // Login itself is case-insensitive on email, so the counter has to be.
        assertThatThrownBy(() -> throttle.check("ana@example.com", ADDRESS))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void theMapDoesNotGrowWithoutBound() {
        LoginThrottle throttle = new LoginThrottle(10, 10_000, 100, Duration.ofMinutes(15), 4);

        // The keys are attacker-supplied; this is the eviction that stops a
        // stream of invented addresses filling the heap.
        for (int i = 0; i < 500; i++) {
            throttle.failed("nobody-" + i + "@example.com", "203.0.113." + (i % 256));
        }

        assertThat(throttle.trackedKeyCount()).isLessThanOrEqualTo(4);
    }

    /**
     * Registration counts *attempts*, not failures, and nothing clears them but
     * the window passing — the opposite of the sign-in counters above. Both
     * halves are the point: a successful sign-up still made an account, and a
     * refused one still cost the lookup that refused it.
     */
    @Test
    void countingEveryRegistrationRatherThanOnlyTheFailedOnes() {
        LoginThrottle throttle = new LoginThrottle(100, 100, 3, Duration.ofMinutes(15), 100);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatCode(() -> throttle.checkRegistration(ADDRESS)).doesNotThrowAnyException();
            throttle.registrationAttempted(ADDRESS);
        }

        assertThatThrownBy(() -> throttle.checkRegistration(ADDRESS))
                .isInstanceOf(RateLimitedException.class);
    }

    /**
     * A run of bad passwords must not also stop somebody signing up, and a run
     * of sign-ups must not lock the sign-in form — they are separate keys for
     * separate arguments, and sharing one would make each limit the other's.
     */
    @Test
    void registrationAndSignInCountSeparately() {
        LoginThrottle throttle = new LoginThrottle(2, 2, 2, Duration.ofMinutes(15), 100);

        throttle.failed("ana@example.com", ADDRESS);
        throttle.failed("ana@example.com", ADDRESS);

        assertThatThrownBy(() -> throttle.check("ana@example.com", ADDRESS))
                .isInstanceOf(RateLimitedException.class);
        assertThatCode(() -> throttle.checkRegistration(ADDRESS)).doesNotThrowAnyException();
    }
}
