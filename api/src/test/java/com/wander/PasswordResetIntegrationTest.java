package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.wander.auth.UserAccountService;
import com.wander.user.GlobalRole;

/**
 * Putting a password back on an instance that sends no mail.
 *
 * Every assertion here is about the link being a *credential*: it works once, it
 * stops working when revoked or expired, it is redeemed anonymously because its
 * whole audience is people who cannot sign in, and it takes every session for
 * the account with it when it is used.
 */
class PasswordResetIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "sturdy-lantern-parade";

    @Autowired
    private UserAccountService accounts;

    private Session admin() {
        String email = "admin-%s@example.com".formatted(unique());
        accounts.create(email, "Administrator", PASSWORD, GlobalRole.ADMIN);
        return login(email, PASSWORD);
    }

    private Object idOf(Session session) {
        return asMap(get(session, "/api/auth/me").getBody()).get("id");
    }

    private Map<String, Object> mint(Session admin, Object userId) {
        ResponseEntity<String> response = post(admin, "/api/admin/accounts/" + userId + "/resets", "{}");
        assertThat(response.getStatusCode().value()).as("minting a reset link").isEqualTo(201);
        return asMap(response.getBody());
    }

    /** The reset endpoints are anonymous, so these deliberately carry no session. */
    private ResponseEntity<String> previewAnonymously(String token) {
        return http().get().uri("/api/auth/reset/" + token).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> redeemAnonymously(String token, String newPassword) {
        // A browser has loaded something before it posts, so it holds the CSRF
        // cookie. CSRF applies to the anonymous endpoints too — see the register
        // and login calls in IntegrationTestBase.
        String csrf = bootstrapCsrf();
        return http().post()
                .uri("/api/auth/reset/" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body("""
                        {"newPassword":"%s"}
                        """.formatted(newPassword))
                .retrieve()
                .toEntity(String.class);
    }

    @Test
    void aLinkSetsThePasswordOnceAndIsThenSpent() {
        Session admin = admin();
        Session user = register("forgetful");

        Map<String, Object> created = mint(admin, idOf(user));
        String token = (String) created.get("token");
        assertThat(token).as("the token is returned exactly once, here").isNotBlank();
        // The client joins this to its own origin; the server behind a proxy
        // does not reliably know the address a browser reached it on.
        assertThat(created.get("path")).isEqualTo("/reset/" + token);
        // What the admin can read back afterwards carries no token, because the
        // server kept only a digest.
        assertThat(asMap(json.writeValueAsString(created.get("reset")))).doesNotContainKey("token");

        // The holder is not signed in — that is the entire point — and is told
        // which account this is for.
        Map<String, Object> preview = asMap(previewAnonymously(token).getBody());
        assertThat(preview.get("email")).isEqualTo(user.email());
        assertThat(preview.get("usable")).isEqualTo(true);

        assertThat(redeemAnonymously(token, NEW_PASSWORD).getStatusCode().value()).isEqualTo(204);

        // The new password works and the old one does not.
        assertThat(attemptLogin(user.email(), NEW_PASSWORD).getStatusCode().value()).isEqualTo(200);
        assertThat(attemptLogin(user.email(), PASSWORD).getStatusCode().value()).isEqualTo(401);

        // Single use. A link that stayed live would be a standing key to the
        // account for whoever else saw the message it was sent in.
        assertThat(asMap(previewAnonymously(token).getBody()).get("usable")).isEqualTo(false);
        assertThat(redeemAnonymously(token, "another-sturdy-phrase").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void redeemingEndsEverySessionForTheAccount() {
        Session admin = admin();
        Session user = register("compromised");
        Object userId = idOf(user);

        String token = (String) mint(admin, userId).get("token");
        assertThat(get(user, "/api/auth/me").getStatusCode().value()).isEqualTo(200);

        assertThat(redeemAnonymously(token, NEW_PASSWORD).getStatusCode().value()).isEqualTo(204);

        // With no exception, unlike changing your own password, which keeps the
        // session doing the changing. Somebody using a reset link is often
        // recovering an account they believe somebody else has a session on, and
        // leaving those alive would make the new password worth very little.
        assertThat(get(user, "/api/auth/me").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aRevokedLinkSaysSoRatherThanVanishing() {
        Session admin = admin();
        Session user = register("revoked");
        Object userId = idOf(user);

        Map<String, Object> created = mint(admin, userId);
        String token = (String) created.get("token");
        Object resetId = asMap(json.writeValueAsString(created.get("reset"))).get("id");

        assertThat(delete(admin, "/api/admin/accounts/" + userId + "/resets/" + resetId)
                .getStatusCode().value()).isEqualTo(204);

        // 200 with a reason, not a 404: the holder is not an attacker, and "ask
        // for another" is something they can act on. A 404 here would read as a
        // mistyped URL.
        Map<String, Object> preview = asMap(previewAnonymously(token).getBody());
        assertThat(preview.get("usable")).isEqualTo(false);
        assertThat((String) preview.get("reason")).contains("revoked");
        assertThat(redeemAnonymously(token, NEW_PASSWORD).getStatusCode().value()).isEqualTo(409);
        assertThat(attemptLogin(user.email(), PASSWORD).getStatusCode().value()).isEqualTo(200);

        // The admin's list is how they know what became of a link they remember
        // sending — the point of keeping revoked rows rather than deleting them.
        List<Map<String, Object>> resets = asList(get(admin, "/api/admin/accounts/" + userId + "/resets").getBody());
        assertThat(resets).singleElement().satisfies(reset -> {
            assertThat(reset.get("status")).isEqualTo("REVOKED");
            assertThat(reset).doesNotContainKey("token");
        });
    }

    @Test
    void aTokenThatDoesNotExistIsA404WhateverIsWrongWithIt() {
        // Always the same answer, so a guesser never learns which attempt found
        // something real. There is nothing to guess — the token is 256 bits from
        // a CSPRNG — but the endpoint should not be the place that says so.
        assertThat(previewAnonymously("not-a-real-token").getStatusCode().value()).isEqualTo(404);
        assertThat(redeemAnonymously("not-a-real-token", NEW_PASSWORD).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void theSamePasswordPolicyAppliesHereAsEverywhereElse() {
        Session admin = admin();
        Session user = register("weak");
        String token = (String) mint(admin, idOf(user)).get("token");

        // A third door onto the same account. A policy that covered registration
        // and change-password but not this one would just be the door people
        // used to get a weak password through.
        assertThat(redeemAnonymously(token, "password12").getStatusCode().value()).isEqualTo(400);
        assertThat(redeemAnonymously(token, "aaaaaaaaaaaa").getStatusCode().value()).isEqualTo(400);
        // Refused, so the link is not spent by a rejected attempt.
        assertThat(asMap(previewAnonymously(token).getBody()).get("usable")).isEqualTo(true);
    }

    @Test
    void aDisabledAccountCanNeitherBeMintedForNorReset() {
        Session admin = admin();
        Session user = register("shut-off");
        Object userId = idOf(user);

        // Minted first, then the account is disabled underneath it.
        String token = (String) mint(admin, userId).get("token");
        assertThat(put(admin, "/api/admin/accounts/" + userId + "/disabled", """
                {"disabled":true}
                """).getStatusCode().value()).isEqualTo(200);

        // A live link must not be a way back into an account somebody
        // deliberately took out of service.
        assertThat(asMap(previewAnonymously(token).getBody()).get("usable")).isEqualTo(false);
        assertThat(redeemAnonymously(token, NEW_PASSWORD).getStatusCode().value()).isEqualTo(409);
        assertThat(attemptLogin(user.email(), NEW_PASSWORD).getStatusCode().value()).isEqualTo(401);

        // And a new one is refused rather than minted and handed over: a link
        // that cannot possibly work is a worse answer than saying so now.
        assertThat(post(admin, "/api/admin/accounts/" + userId + "/resets", "{}").getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    void twoRequestsRacingForOneLinkSetOnePassword() throws Exception {
        Session admin = admin();
        Session user = register("racing");
        String token = (String) mint(admin, idOf(user)).get("token");

        String first = NEW_PASSWORD;
        String second = "another-sturdy-phrase";
        List<Callable<Integer>> both = List.of(
                () -> redeemAnonymously(token, first).getStatusCode().value(),
                () -> redeemAnonymously(token, second).getStatusCode().value());

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            statuses = pool.invokeAll(both).stream().map(PasswordResetIntegrationTest::value).sorted().toList();
        }

        // Without the row lock both transactions find an unused link, both set a
        // password, and the second silently overwrites the first — whoever typed
        // the losing one cannot sign in and has no way to find out why. Every
        // individual request would have looked perfectly correct.
        assertThat(statuses).containsExactly(204, 409);
        long usable = List.of(first, second).stream()
                .filter(password -> attemptLogin(user.email(), password).getStatusCode().value() == 200)
                .count();
        assertThat(usable).as("exactly one of the two passwords was set").isEqualTo(1);
    }

    private static Integer value(Future<Integer> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
