package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.wander.auth.UserAccountService;
import com.wander.mail.MailClient;
import com.wander.user.GlobalRole;

/**
 * Asking for your own reset link, on an instance that can send mail.
 *
 * {@code PasswordResetIntegrationTest} covers the link itself — single use,
 * expiry, revocation, the sessions it ends. None of that is retested here,
 * because the self-service path mints the *same* row through the same
 * {@code SecureToken} and redeems it through the same locked read. What is new
 * is the half in front of it, and almost all of it is about what the endpoint
 * refuses to say.
 *
 * <p>Its own context on purpose ({@code @TestPropertySource}), because
 * {@code wander.mail.enabled} is false everywhere else and the whole suite would
 * otherwise be running with a feature switched on that only this file wants.
 * {@link MailClient} is the seam, replaced here exactly as {@code GeocoderClient}
 * and {@code WeatherClient} are — a test suite that sent real mail would be a
 * test suite that needed a relay and somebody's inbox.
 */
@TestPropertySource(properties = {
        "wander.mail.enabled=true",
        "wander.mail.from=noreply@wander.test",
        "wander.mail.reset-expires-minutes=60",
        // Higher than the production default, because several tests here ask
        // from the same address — every request in this suite arrives from
        // 127.0.0.1, which is the same reason LoginThrottleIntegrationTest gets
        // a context to itself.
        "wander.mail.max-requests-per-address=50" })
class SelfServiceResetIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "sturdy-lantern-parade";

    /** Pulls the token out of the message body, which is the only place it exists. */
    private static final Pattern LINK = Pattern.compile("/reset/([A-Za-z0-9_-]+)");

    @Autowired
    private UserAccountService accounts;

    @MockitoBean
    private MailClient mail;

    @BeforeEach
    void mailIsWorking() {
        reset(mail);
        when(mail.enabled()).thenReturn(true);
        when(mail.send(anyString(), anyString(), anyString())).thenReturn(true);
    }

    private String anAccount(String prefix) {
        String email = "%s-%s@example.com".formatted(prefix, unique());
        accounts.create(email, prefix, PASSWORD, GlobalRole.USER);
        return email;
    }

    private ResponseEntity<String> ask(String email) {
        String csrf = bootstrapCsrf();
        return http().post()
                .uri("/api/auth/reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("{\"email\":\"%s\"}".formatted(email))
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * The whole journey, and the only test here that proves the feature works
     * rather than that it keeps quiet.
     */
    @Test
    void somebodyLockedOutAsksForALinkAndSetsANewPassword() {
        String email = anAccount("vera");

        assertThat(ask(email).getStatusCode().value()).isEqualTo(204);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(email), anyString(), body.capture());

        Matcher matcher = LINK.matcher(body.getValue());
        assertThat(matcher.find()).as("the message carries a /reset/<token> link").isTrue();
        String token = matcher.group(1);

        // The link is redeemable anonymously, like an administrator's.
        String csrf = bootstrapCsrf();
        ResponseEntity<String> redeemed = http().post()
                .uri("/api/auth/reset/" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("{\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD))
                .retrieve()
                .toEntity(String.class);
        assertThat(redeemed.getStatusCode().value()).isEqualTo(204);

        // It took, and the old one is genuinely gone.
        assertThat(login(email, NEW_PASSWORD)).isNotNull();
        assertThat(attemptLogin(email, PASSWORD).getStatusCode().value()).isEqualTo(401);
    }

    /**
     * The important one.
     *
     * An address with no account and an address with one must be indistinguishable
     * from outside, or this endpoint becomes a way to ask "does this person use
     * this instance" — which on a trip planner is most of what somebody wanted to
     * know. Same status, same empty body, and nothing sent.
     */
    @Test
    void anUnknownAddressLooksExactlyLikeAKnownOne() {
        String known = anAccount("mira");

        ResponseEntity<String> forKnown = ask(known);
        ResponseEntity<String> forUnknown = ask("nobody-%s@example.com".formatted(unique()));

        assertThat(forUnknown.getStatusCode()).isEqualTo(forKnown.getStatusCode());
        assertThat(forUnknown.getBody()).isEqualTo(forKnown.getBody());
        assertThat(forUnknown.getStatusCode().value()).isEqualTo(204);

        // One message, for the address that exists. The other produced nothing —
        // which is the half a passing status code would not have shown.
        verify(mail, times(1)).send(anyString(), anyString(), anyString());
        verify(mail).send(eq(known), anyString(), anyString());
    }

    /**
     * A disabled account is silent too, and for a sharper reason than tidiness:
     * a live link must never be a way back into an account somebody deliberately
     * shut off. {@code create} refuses an administrator outright for the same
     * rule; here it cannot say so without admitting the account exists.
     */
    @Test
    void aDisabledAccountIsNeitherToldNorMailed() {
        String email = anAccount("shut");
        String adminEmail = "admin-%s@example.com".formatted(unique());
        accounts.create(adminEmail, "Administrator", PASSWORD, GlobalRole.ADMIN);
        Session admin = login(adminEmail, PASSWORD);

        Object id = asList(get(admin, "/api/admin/accounts").getBody()).stream()
                .filter(row -> email.equals(row.get("email")))
                .findFirst().orElseThrow().get("id");
        assertThat(put(admin, "/api/admin/accounts/" + id + "/disabled", "{\"disabled\":true}")
                .getStatusCode().value()).isEqualTo(200);

        assertThat(ask(email).getStatusCode().value()).isEqualTo(204);
        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    /**
     * {@code /api/auth/reset/request} and {@code /api/auth/reset/{token}} are both
     * POST on the same controller, so the literal segment has to win or asking for
     * a link would be read as redeeming one called "request" — a 404 for everybody,
     * every time. Spring resolves literals ahead of path variables, and this is
     * the test that says so out loud rather than leaving it to be rediscovered.
     */
    @Test
    void theRequestPathIsNotSwallowedByTheTokenPath() {
        assertThat(ask(anAccount("route")).getStatusCode().value()).isEqualTo(204);
        verify(mail, times(1)).send(anyString(), anyString(), anyString());
    }

    /** Shape is the one thing it may be picky about: a 400 here names no account. */
    @Test
    void somethingThatIsNotAnAddressIsRefusedWithoutTouchingTheRelay() {
        String csrf = bootstrapCsrf();
        ResponseEntity<String> response = http().post()
                .uri("/api/auth/reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("{\"email\":\"not-an-address\"}")
                .retrieve()
                .onStatus(status -> true, (req, res) -> {
                })
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    /** The login page has to know, or it draws a link that goes nowhere useful. */
    @Test
    void theSignInPageIsToldTheInstanceCanSend() {
        Map<String, Object> config = asMap(
                http().get().uri("/api/config/sign-in").retrieve().toEntity(String.class).getBody());
        assertThat(config).containsEntry("passwordResetEnabled", true);
    }
}
