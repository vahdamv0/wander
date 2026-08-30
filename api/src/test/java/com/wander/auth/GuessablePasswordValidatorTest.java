package com.wander.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The password policy, tested as the pure function it is.
 *
 * Worth a unit test for the same reason money parsing is: a wrong answer here
 * throws nothing and fails no request — it either lets a guessable password
 * through, which nobody notices until an account is taken, or refuses a
 * perfectly good passphrase, which reads to the person typing it as the form
 * being broken.
 */
class GuessablePasswordValidatorTest {

    private final GuessablePasswordValidator validator = new GuessablePasswordValidator();

    private boolean accepts(String password) {
        return validator.isValid(password, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The shape this exists for: an old eight-character password with
            // two digits stuck on the end to clear the length bound.
            "password12",
            "Password123",
            "PASSWORD1234",
            "welcome2025",
            "qwerty12345",
            "iloveyou123",
            "letmein1234" })
    void refusingThePasswordsThatAreActuallyGuessed(String password) {
        assertThat(accepts(password)).as(password).isFalse();
    }

    /** Case is not a policy. `Password12` is `password12` to everybody but the form. */
    @Test
    void theListIsCaseInsensitive() {
        assertThat(accepts("PaSsWoRd12")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aaaaaaaaaa",
            "0000000000",
            "!!!!!!!!!!!!" })
    void refusingOneCharacterHeldDown(String password) {
        assertThat(accepts(password)).as(password).isFalse();
    }

    /**
     * Runs are generated rather than listed, so any window of one counts and so
     * does its reverse — otherwise the policy is a puzzle with an obvious
     * answer.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "1234567890",
            "3456789012",
            "0987654321",
            "qwertyuiop",
            "poiuytrewq",
            "abcdefghij",
            "jihgfedcba" })
    void refusingAStraightRunInEitherDirection(String password) {
        assertThat(accepts(password)).as(password).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The suite's own password, which every integration test registers
            // with — if the policy ever swallows this, 174 tests go red at once
            // and the cause is this file.
            "correct-horse-battery",
            "a-quiet-week-in-kanazawa",
            "Tuesday!Ferry!Naoshima",
            "gK7#pw2Lm9xQ" })
    void leavingRealPasswordsAlone(String password) {
        assertThat(accepts(password)).as(password).isTrue();
    }

    /**
     * Short and blank inputs are @Size and @NotBlank's business. Answering false
     * here as well would make one bad password produce two complaints, and the
     * form would say two things at once about the same field.
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "short", "password" })
    void leavingTheLengthBoundToDoItsOwnJob(String password) {
        assertThat(accepts(password)).as(password).isTrue();
    }

    @Test
    void nullIsNotThisValidatorsProblemEither() {
        assertThat(accepts(null)).isTrue();
    }
}
