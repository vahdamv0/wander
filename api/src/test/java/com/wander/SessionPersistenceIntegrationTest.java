package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * A session outlives the process that created it.
 *
 * This matters more here than in an application that hands out tokens: the
 * session *is* the credential — an httpOnly cookie, nothing in localStorage — so
 * while sessions lived in Tomcat's heap, every restart signed out every user. On
 * a self-hosted instance that means a deploy logs out the household, and live
 * sync comes back from it asking people to log in again rather than reconnecting.
 *
 * The interesting test is the second one, and it earns its awkwardness: the only
 * honest way to prove a session survives a restart is to *have* a second
 * instance. It starts one on the same database, in the same JVM but with its own
 * Tomcat and its own heap, and presents the first instance's cookie to it.
 */
class SessionPersistenceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Test
    void signingInWritesTheSessionToPostgres() {
        Session user = register("persisted");

        // Not a detail of the store leaking into the test: this is the assertion
        // that the store *is* the database. Fall back to an in-memory repository
        // and everything else in the suite still passes.
        Long sessions = jdbc.queryForObject(
                "SELECT count(*) FROM spring_session WHERE principal_name = ?", Long.class, user.email());
        assertThat(sessions).as("a session row for %s", user.email()).isEqualTo(1L);

        // And signing out takes it away again, rather than leaving a usable row
        // behind until it expires.
        assertThat(post(user, "/api/auth/logout", "").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM spring_session WHERE principal_name = ?", Long.class, user.email()))
                .as("the row is gone after logout").isZero();
    }

    @Test
    void aSessionOutlivesTheInstanceThatIssuedIt() {
        Session user = register("restarted");
        assertThat(asMap(get(user, "/api/auth/me").getBody())).containsEntry("email", user.email());

        // A second instance: same database, its own Tomcat, its own heap. As far
        // as the session store is concerned this is the restarted process.
        // Passed as command-line arguments, not builder properties: `properties()`
        // lands in Spring's *default* property source, which application.yml
        // overrides — so the second instance would quietly go looking for a
        // database on localhost:5432 instead of the container.
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(WanderApplication.class)
                .profiles("test")
                .run(
                        "--spring.datasource.url=" + environment.getProperty("spring.datasource.url"),
                        "--spring.datasource.username=" + environment.getProperty("spring.datasource.username"),
                        "--spring.datasource.password=" + environment.getProperty("spring.datasource.password"),
                        "--server.port=0")) {

            int otherPort = Integer.parseInt(
                    restarted.getEnvironment().getRequiredProperty("local.server.port"));

            ResponseEntity<String> response = RestClient.builder()
                    .baseUrl("http://localhost:" + otherPort)
                    .defaultStatusHandler(status -> true, (request, ignored) -> {
                    })
                    .build()
                    .get()
                    .uri("/api/auth/me")
                    // The cookie the *first* instance issued, and nothing else.
                    .header(HttpHeaders.COOKIE, user.cookie())
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getStatusCode().value())
                    .as("the cookie from the previous instance still authenticates")
                    .isEqualTo(200);
            assertThat(asMap(response.getBody())).containsEntry("email", user.email());
        }
    }
}
