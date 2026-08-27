package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wander.user.UserRepository;

/**
 * The walking-skeleton test: the context starts, Flyway migrates a real
 * Postgres, the first-boot admin exists, and the public healthcheck answers.
 */
class ApplicationBootTest extends IntegrationTestBase {

    @Autowired
    private UserRepository users;

    @Test
    void healthIsPublic() {
        var response = http().get().uri("/api/health").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"ok\"");
    }

    @Test
    void firstBootSeedsAnAdmin() {
        assertThat(users.findByEmailIgnoreCase("admin@wander.local")).isPresent();
    }
}
