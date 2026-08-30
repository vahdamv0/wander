package com.wander.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * The check behind {@link GuessablePassword}: a bundled list, plus the patterns
 * that are too numerous to list.
 *
 * The list is loaded once into a static set — it is a few kilobytes and read on
 * every registration, so re-reading it per validator instance would be silly and
 * holding it per instance would be several copies of the same thing.
 */
public class GuessablePasswordValidator implements ConstraintValidator<GuessablePassword, String> {

    /**
     * Below this, {@code @Size} on the DTO has already refused it, and answering
     * with a second complaint about the same password only makes the form say
     * two things at once.
     */
    private static final int SIZE_HANDLES_IT_BELOW = 10;

    /**
     * The runs a keyboard offers. Checked in both directions, so `0987654321`
     * and `poiuytrewq` are caught by the same two lines that catch their
     * reverses, and checked as *substrings* so any window of one counts —
     * `3456789012` is no better a password than `1234567890`.
     */
    private static final Set<String> RUNS = Set.of(
            "01234567890123456789",
            "abcdefghijklmnopqrstuvwxyz",
            "qwertyuiopasdfghjklzxcvbnm",
            "qwertzuiopasdfghjklyxcvbnm",
            "azertyuiopqsdfghjklmwxcvbn");

    private static final Set<String> COMMON = load();

    private static Set<String> load() {
        ClassPathResource resource = new ClassPathResource("common-passwords.txt");
        try (InputStream in = resource.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(line -> line.strip().toLowerCase(Locale.ROOT))
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    // A shorter entry could never match something @Size has
                    // already passed, so it would only ever be dead weight in
                    // the set.
                    .filter(line -> line.length() >= SIZE_HANDLES_IT_BELOW)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException ex) {
            // The file is packaged in the jar beside the class that reads it. If
            // it is missing, the build is broken in a way that must not be
            // discovered as "weak passwords are quietly accepted".
            throw new UncheckedIOException("common-passwords.txt is missing from the classpath", ex);
        }
    }

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        // @NotBlank and @Size own these cases. Answering true here is what keeps
        // one bad password from producing three messages.
        if (password == null || password.length() < SIZE_HANDLES_IT_BELOW) {
            return true;
        }

        String lowered = password.toLowerCase(Locale.ROOT);
        return !COMMON.contains(lowered) && !oneCharacterRepeated(lowered) && !straightRun(lowered);
    }

    /** `aaaaaaaaaa`, and every other password with one character in it. */
    private static boolean oneCharacterRepeated(String password) {
        return password.chars().distinct().count() == 1;
    }

    /**
     * A straight run along a keyboard row or the digits, forwards or backwards.
     * Caught here rather than listed because the windows of a single run are
     * more entries than the rest of the file put together.
     */
    private static boolean straightRun(String password) {
        String reversed = new StringBuilder(password).reverse().toString();
        return RUNS.stream().anyMatch(run -> run.contains(password) || run.contains(reversed));
    }
}
