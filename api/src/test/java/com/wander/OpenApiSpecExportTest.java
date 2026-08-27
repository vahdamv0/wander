package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Exports the OpenAPI document to build/openapi.json, which is what
 * `npm run api:gen` turns into the typed Angular client.
 *
 * Doing it from a test rather than a running server keeps the codegen loop
 * offline and CI-friendly: no port to wait on, and the spec can never describe a
 * build that does not pass its own tests.
 */
class OpenApiSpecExportTest extends IntegrationTestBase {

    @Test
    void exportsSpec() throws Exception {
        String spec = http().get().uri("/v3/api-docs").retrieve().toEntity(String.class).getBody();

        assertThat(spec).contains("/api/trips");
        assertThat(spec).contains("/api/auth/login");
        // The hash must never reach a response schema.
        assertThat(spec).doesNotContain("passwordHash");

        Path target = Path.of(System.getProperty("wander.spec.out", "build/openapi.json"));
        Files.createDirectories(target.getParent());
        Files.writeString(target, spec);
    }
}
