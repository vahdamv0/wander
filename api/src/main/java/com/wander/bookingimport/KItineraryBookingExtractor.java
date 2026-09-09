package com.wander.bookingimport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.wander.config.WanderProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * KItinerary, KDE Itinerary's extraction engine, as a subprocess.
 *
 * The only implementation of {@link BookingExtractor}, and the reason there is no
 * hand-written parser beside it. It carries 349 provider extractors — airlines,
 * railways, hotel chains, and the white-label booking platforms small hotels
 * actually run on (Amadeus, Availpro, Caesar Data, Direct-Book) — plus
 * schema.org JSON-LD *and* microdata, Apple Wallet passes, IATA boarding-pass
 * barcodes, phone numbers through libphonenumber, and its own airport database.
 * That last one is why nothing in this project ships a table of IATA codes: the
 * engine resolves {@code HND} to {@code Asia/Tokyo} itself, which is the one
 * field a printed ticket can never supply and the field the whole reservation
 * model is built around.
 *
 * Being a separate process is not a compromise, it is the point. It is C++ and
 * Qt behind poppler and ZXing, parsing a file somebody uploaded — so a malformed
 * PDF that segfaults or wedges the parser kills a child process on a timeout,
 * while the same library linked in would take the instance down with it. Nothing
 * is linked, nothing is generated at build time, and the Gradle build knows
 * nothing about any of it: this is the relationship {@code backup/backup.sh} has
 * with {@code pg_dump}.
 *
 * It is optional. An image built without it leaves {@link #isAvailable()} false
 * and the calendar reader carries the feature on its own.
 */
@Component
public class KItineraryBookingExtractor implements BookingExtractor {

    private static final Logger log = LoggerFactory.getLogger(KItineraryBookingExtractor.class);

    private static final String BINARY_NAME = "kitinerary-extractor";

    /**
     * Where the binary lives, in the order worth looking.
     *
     * It is installed into a private {@code libexec} directory rather than onto
     * the path, so finding it is a real step and not a formality. The Alpine
     * layout comes first because that is what this project's own image ships;
     * the Debian multiarch triplet is globbed after it so a self-hoster running
     * the jar on an ordinary machine is also covered.
     */
    private static final List<String> FIXED_CANDIDATES = List.of(
            "/usr/lib/libexec/kf6/" + BINARY_NAME,
            "/usr/local/bin/" + BINARY_NAME,
            "/usr/bin/" + BINARY_NAME);

    /** 0700, so nothing else on the box can read a confirmation mid-extraction. */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    private final ObjectMapper json;
    private final WanderProperties.BookingImport settings;

    /** Absolute, resolved once at startup, or null when there is no extractor here. */
    private final Path binary;

    /**
     * Extra extractor scripts of our own, or null for none.
     *
     * Resolved once at startup like the binary, and for a weaker version of the
     * same reason: what is on this path is code the extractor executes, so it is
     * settled while the instance is starting rather than looked up per upload.
     * A configured path that is not a directory is logged and dropped — passing
     * it anyway would have the extractor load nothing and say nothing, which is
     * indistinguishable from a document it could not read.
     */
    private final Path searchPath;

    /**
     * Where a document is written for the extractor to read. {@code /dev/shm}
     * when it is writable, which keeps an uploaded confirmation out of persistent
     * storage entirely — the file holds a booking reference and sometimes a
     * passport number, and this project's whole reason for never storing an
     * invitation token is that backups leave the machine.
     */
    private final Path workDir;

    public KItineraryBookingExtractor(WanderProperties properties, ObjectMapper json) {
        this.json = json;
        this.settings = properties.bookingImport();
        this.binary = settings.enabled() ? locateBinary(settings.extractorPath()) : null;
        this.searchPath = binary == null ? null : locateSearchPath(settings.extractorSearchPath());
        this.workDir = chooseWorkDir();

        if (!settings.enabled()) {
            log.info("Booking import is switched off (wander.booking-import.enabled)");
        } else if (binary == null) {
            log.info("No {} found — booking import will read calendar attachments only", BINARY_NAME);
        } else if (searchPath == null) {
            log.info("Booking import using {}", binary);
        } else {
            log.info("Booking import using {} with extra extractors from {}", binary, searchPath);
        }
    }

    @Override
    public boolean isAvailable() {
        return binary != null;
    }

    @Override
    public List<JsonNode> extract(byte[] content, String filename, Instant contextDate) {
        if (binary == null) {
            return List.of();
        }

        Path dir = null;
        try {
            dir = Files.createTempDirectory(workDir, "wander-import-", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            // The extension decides what the extractor thinks it is reading, so
            // it has to survive the round trip through a temp file. The caller
            // has already checked it against an allow-list, which is what makes
            // pasting it into a path safe.
            Path input = dir.resolve("upload" + extensionOf(filename));
            Path output = dir.resolve("stdout.json");
            Files.write(input, content);

            List<String> command = new ArrayList<>(List.of(binary.toString(),
                    // Dates written without a year resolve against this. Without
                    // it a ticket saying "14 Oct" lands in whichever October the
                    // extractor assumes, and it silently assumes today's.
                    "-c", contextDate.toString()));
            if (searchPath != null) {
                command.add("--additional-search-path");
                command.add(searchPath.toString());
            }
            command.add(input.toString());

            Process process = new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    // Discarded on purpose. Every one of the 349 extractors is
                    // tried against every document and most do not match, so
                    // stderr is a steady stream of expected script errors — and
                    // merging it into stdout would corrupt the JSON we came for.
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            if (!process.waitFor(settings.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Extractor timed out after {}s on an uploaded {}", settings.timeoutSeconds(),
                        extensionOf(filename));
                return List.of();
            }

            return parse(readCapped(output));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (IOException | RuntimeException ex) {
            // An unreadable document is a nicety not delivered, never a 500. The
            // message is logged without the filename or any of the content: a
            // booking reference in a log file is the thing this feature's
            // never-store-the-upload design exists to avoid writing down.
            log.warn("Extraction failed: {}", ex.getMessage());
            return List.of();
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * The extractor's output, up to a cap.
     *
     * Capped because the process is not ours and a runaway one must not be able
     * to spend the JVM's heap. Over the cap the answer is nothing rather than a
     * truncated parse: half a JSON array is not a smaller set of bookings, it is
     * a syntax error, and treating it as one is how a partial read becomes a
     * plausible wrong answer.
     */
    private byte[] readCapped(Path output) throws IOException {
        if (!Files.exists(output)) {
            return new byte[0];
        }
        long size = Files.size(output);
        if (size > settings.maxOutputBytes()) {
            log.warn("Extractor produced {} bytes, over the {} byte cap — discarding", size,
                    settings.maxOutputBytes());
            return new byte[0];
        }
        return Files.readAllBytes(output);
    }

    /**
     * The reservation nodes in the extractor's JSON.
     *
     * It answers a top-level array, but a single object is accepted too: that is
     * cheap insurance against a version that decides to unwrap a lone result, and
     * the alternative to insurance here is silently importing nothing.
     */
    private List<JsonNode> parse(byte[] stdout) {
        if (stdout.length == 0) {
            return List.of();
        }
        JsonNode root = json.readTree(stdout);
        if (root.isArray()) {
            List<JsonNode> nodes = new ArrayList<>();
            root.forEach(nodes::add);
            return nodes;
        }
        return root.isObject() ? List.of(root) : List.of();
    }

    /**
     * The extension, lowercased, dot included — or empty.
     *
     * Taken from the last dot and nothing else: the caller validated it against
     * an allow-list before this ran, so there is no separator or traversal
     * sequence left to strip. Empty is fine to hand the extractor; it sniffs.
     */
    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }

    /**
     * The extra-extractor directory, as an absolute path, or null.
     *
     * Blank is the ordinary answer for a jar run outside this project's image and
     * means the 349 built-in extractors and nothing else. A path that is set but
     * is not a readable directory is a misconfiguration worth a warning: the
     * extractor would accept the argument, load nothing from it, and go on
     * answering "nothing recognised" for the documents those scripts exist to
     * read.
     */
    private static Path locateSearchPath(String configured) {
        if (configured.isBlank()) {
            return null;
        }
        Path path = Path.of(configured);
        if (!Files.isDirectory(path) || !Files.isReadable(path)) {
            log.warn("wander.booking-import.extractor-search-path={} is not a readable directory", configured);
            return null;
        }
        return path.toAbsolutePath();
    }

    /**
     * The binary, as an absolute path, resolved once.
     *
     * Once matters. Re-resolving through {@code PATH} on every call means the
     * program that runs is whatever {@code PATH} pointed at that moment, so
     * anyone able to write to a directory on it could have their own binary run
     * as the application user. Probing with {@code --version} is also how a
     * present-but-unrunnable file — wrong architecture, missing library — is
     * found at startup rather than the first time somebody imports something.
     */
    private static Path locateBinary(String configured) {
        if (!configured.isBlank()) {
            Path explicit = Path.of(configured);
            if (runnable(explicit)) {
                return explicit.toAbsolutePath();
            }
            log.warn("wander.booking-import.extractor-path={} is not a runnable extractor", configured);
            return null;
        }

        for (String candidate : FIXED_CANDIDATES) {
            Path path = Path.of(candidate);
            if (runnable(path)) {
                return path.toAbsolutePath();
            }
        }

        Path multiarch = findDebianMultiarch();
        if (multiarch != null) {
            return multiarch;
        }

        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path path = Path.of(dir, BINARY_NAME);
                if (runnable(path)) {
                    return path.toAbsolutePath();
                }
            }
        }
        return null;
    }

    /** Debian and Ubuntu bury it under the architecture triplet: /usr/lib/aarch64-linux-gnu/libexec/kf6/. */
    private static Path findDebianMultiarch() {
        Path lib = Path.of("/usr/lib");
        if (!Files.isDirectory(lib)) {
            return null;
        }
        try (Stream<Path> dirs = Files.list(lib)) {
            return dirs.map(dir -> dir.resolve("libexec/kf6/" + BINARY_NAME))
                    .filter(KItineraryBookingExtractor::runnable)
                    .findFirst()
                    .map(Path::toAbsolutePath)
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean runnable(Path path) {
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            return false;
        }
        try {
            Process probe = new ProcessBuilder(path.toString(), "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!probe.waitFor(5, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * {@code /dev/shm} for preference, so an uploaded confirmation lives in
     * memory for the tenth of a second it takes to read and never touches a disk
     * that gets backed up. Falls back to the ordinary temp directory, which is
     * what a self-hoster running the jar outside a container will get.
     */
    private static Path chooseWorkDir() {
        Path shm = Path.of("/dev/shm");
        if (Files.isDirectory(shm) && Files.isWritable(shm)) {
            return shm;
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    /**
     * Deletes the working directory, and is in a {@code finally} for one reason:
     * the file inside it is somebody's booking confirmation. A failed extraction
     * must not be the path that leaves it lying around.
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: it is a temp directory, and failing to tidy up
                    // must not turn a successful import into an error.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }

    /** Only for the log line at startup and the tests; not part of the seam. */
    Path binaryPath() {
        return binary;
    }
}
