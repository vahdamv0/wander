package com.wander.bookingimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The extra extractor scripts in {@code extractors/} are wired up correctly.
 *
 * Cheap, and it earns its place because every way of getting this wrong is
 * silent. KItinerary loads a manifest whose script is missing, whose JSON does
 * not parse, or whose entry point was renamed by loading *nothing* and saying
 * nothing — and the visible result is an import that answers "nothing in that
 * file was recognised as a booking", which is also what a document nobody wrote
 * an extractor for answers. There is no failing request and no log line to tell
 * the two apart.
 *
 * It cannot run the extractor: the binary is absent from CI by design, which is
 * the same reason the import integration tests are calendar-only. So this
 * asserts on the shape of the files, which is the half that a rename breaks.
 */
class ExtractorManifestTest {

    /**
     * Gradle runs a test with the subproject as its working directory, so the
     * repository root is one up. Both are tried rather than assuming, because
     * running the suite from the root is a normal thing to do from an IDE.
     */
    private static Path extractorDirectory() {
        for (String candidate : List.of("../extractors", "extractors")) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new IllegalStateException("extractors/ not found from " + Path.of("").toAbsolutePath());
    }

    private static List<Path> filesEndingIn(String suffix) throws IOException {
        try (Stream<Path> files = Files.list(extractorDirectory())) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void everyManifestNamesAScriptThatExistsAndDefinesItsEntryPoint() throws IOException {
        List<Path> manifests = filesEndingIn(".json");
        assertThat(manifests).isNotEmpty();

        ObjectMapper json = new ObjectMapper();
        for (Path manifest : manifests) {
            JsonNode root = json.readTree(Files.readAllBytes(manifest));

            assertThat(root.path("mimeType").asString(""))
                    .as("%s declares the document type it applies to", manifest)
                    .isNotBlank();
            assertThat(root.path("filter").isArray() && !root.path("filter").isEmpty())
                    .as("%s carries a filter — without one the script is offered every document", manifest)
                    .isTrue();

            String script = root.path("script").asString("");
            String function = root.path("function").asString("");
            assertThat(script).as("%s names its script", manifest).isNotBlank();
            assertThat(function).as("%s names its entry point", manifest).isNotBlank();

            Path scriptFile = manifest.resolveSibling(script);
            assertThat(scriptFile).as("the script %s names", manifest).exists();
            assertThat(Files.readString(scriptFile, StandardCharsets.UTF_8))
                    .as("%s defines %s, the function %s dispatches to", scriptFile, function, manifest)
                    .contains("function " + function + "(");
        }
    }

    @Test
    void noScriptIsOrphaned() throws IOException {
        List<String> referenced = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        for (Path manifest : filesEndingIn(".json")) {
            referenced.add(json.readTree(Files.readAllBytes(manifest)).path("script").asString(""));
        }

        for (Path script : filesEndingIn(".js")) {
            assertThat(referenced)
                    .as("%s is referenced by a manifest — a script with none is never loaded", script)
                    .contains(script.getFileName().toString());
        }
    }

    /**
     * The reason {@code ana-fullyear.js} may sit beside the built-in {@code ana}
     * extractor at all: it matches a four-digit year only, so exactly one of the
     * two ever matches a given ticket. Loosen this to two-or-four and a
     * Japan-issued ticket matches both scripts and every leg is imported twice
     * — which would look like a wander bug and would not be one.
     *
     * It asserts on the leg-matching line rather than on the file, because the
     * file explains itself: the comment quotes the upstream regex this one
     * replaces, and a whole-file search finds that quotation and calls it a
     * regression.
     */
    @Test
    void theAnaFixStaysDisjointFromTheExtractorItPatches() throws IOException {
        List<String> legLines = Files
                .readAllLines(extractorDirectory().resolve("ana-fullyear.js"), StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains(".match(/") && line.contains("\\]"))
                .toList();
        assertThat(legLines).as("the line matching a leg of the itinerary").hasSize(1);

        String legs = legLines.getFirst();
        assertThat(legs).as("a four-digit year, at both ends of the leg")
                .containsOnlyOnce("(\\d{2}[A-Z]{3}\\d{4}) +[A-Z]{3} +(\\d{4}).*")
                .contains("(\\d{2}[A-Z]{3}\\d{4}) +[A-Z]{3} +(\\d{4}) *");
        assertThat(legs).as("and never the two-digit year the built-in extractor already handles")
                .doesNotContain("\\d{2}[A-Z]{3}\\d{2})");
    }
}
