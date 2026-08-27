package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

// Boot 4 ships Jackson 3, whose ObjectMapper lives under tools.jackson.
import tools.jackson.databind.ObjectMapper;

/**
 * Shared HTTP plumbing for the integration tests.
 *
 * Uses a plain RestClient with status handling switched off, so a 401 or 404 is
 * a value to assert on rather than a thrown exception — these tests are mostly
 * about which status an endpoint returns.
 */
@PostgresIntegrationTest
abstract class IntegrationTestBase {

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper json;

    /** A logged-in browser: the session cookie plus the CSRF token that pairs with it. */
    protected record Session(String cookie, String csrf) {
    }

    protected RestClient http() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // Every status is a normal response; nothing throws.
                .defaultStatusHandler(status -> true, (request, response) -> {
                })
                .build();
    }

    protected static String unique() {
        return Long.toHexString(System.nanoTime());
    }

    /**
     * Picks up an XSRF-TOKEN cookie the way a browser does — by loading
     * something first. Every state-changing request needs it, login and register
     * included, and an anonymous request that fails the CSRF check comes back
     * 401 rather than 403 (Spring treats access-denied-while-anonymous as
     * "authenticate first"), which is a confusing way to learn this.
     */
    protected String bootstrapCsrf() {
        ResponseEntity<Void> response = http().get().uri("/api/health").retrieve().toBodilessEntity();
        String csrf = cookieValue(response, "XSRF-TOKEN");
        assertThat(csrf).as("XSRF-TOKEN cookie on a plain GET").isNotNull();
        return csrf;
    }

    protected Session register(String namePrefix) {
        String email = "%s-%s@example.com".formatted(namePrefix, unique());
        String csrf = bootstrapCsrf();
        String body = """
                {"email":"%s","displayName":"%s","password":"correct-horse-battery"}
                """.formatted(email, namePrefix);

        ResponseEntity<String> response = http().post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).as("register %s", email).isEqualTo(201);

        String session = cookieValue(response, "JSESSIONID");
        assertThat(session).as("session cookie after register").isNotNull();
        // The CSRF token is cookie-backed and survives the new session id.
        String refreshed = cookieValue(response, "XSRF-TOKEN");
        return new Session("JSESSIONID=" + session, refreshed != null ? refreshed : csrf);
    }

    /** The value of one Set-Cookie on a response, or null if it was not set. */
    private static String cookieValue(ResponseEntity<?> response, String name) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies) {
            String pair = cookie.contains(";") ? cookie.substring(0, cookie.indexOf(';')) : cookie;
            if (pair.startsWith(name + "=")) {
                String value = pair.substring(name.length() + 1);
                // A cleared cookie is an empty value; that is not a token.
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }


    protected ResponseEntity<String> get(Session session, String path) {
        return http().get().uri(path)
                .headers(headers -> auth(headers, session))
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * For a path whose query string is already percent-encoded.
     * {@code uri(String)} treats its argument as a template and would encode the
     * `%` again, so `%20` arrives at the server as a literal "%20".
     */
    protected ResponseEntity<String> get(Session session, java.net.URI uri) {
        return http().get().uri(uri)
                .headers(headers -> auth(headers, session))
                .retrieve()
                .toEntity(String.class);
    }

    protected ResponseEntity<String> post(Session session, String path, String body) {
        return http().post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> auth(headers, session))
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    protected ResponseEntity<String> put(Session session, String path, String body) {
        return http().put().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> auth(headers, session))
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    protected ResponseEntity<String> delete(Session session, String path) {
        return http().delete().uri(path)
                .headers(headers -> auth(headers, session))
                .retrieve()
                .toEntity(String.class);
    }

    private void auth(HttpHeaders headers, Session session) {
        headers.add(HttpHeaders.COOKIE, session.cookie() + "; XSRF-TOKEN=" + session.csrf());
        // Exactly what Angular's HttpClient sends on its own.
        headers.add("X-XSRF-TOKEN", session.csrf());
    }

    protected Map<String, Object> asMap(String body) {
        return json.readValue(body, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    protected List<Map<String, Object>> asList(String body) {
        return json.readValue(body, new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
        });
    }
}
