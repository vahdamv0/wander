package com.wander.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Refuses the passwords that are actually guessed.
 *
 * Sits beside {@code @Size(min = 10)} rather than replacing it, because the two
 * answer different questions: length is what makes a password expensive to
 * search for, and this is what stops somebody clearing the length bound by
 * writing `password12`. Ten characters of nothing is still nothing.
 *
 * **A list, not character classes.** Requiring a capital, a digit and a symbol
 * is the rule everybody has met and it produces `Password1!` — which is on every
 * list there is. Composition rules move people toward a small, predictable set
 * while making the password harder to remember; a blocklist refuses that set
 * directly and leaves a long passphrase alone, which is the thing worth
 * encouraging.
 *
 * **No breach-list API call.** Checking a password against a service upstream
 * would put an outbound dependency on the one page that has to work on an
 * instance with no outbound network, hand a prefix of somebody's password hash
 * to a third party, and need a rate limit of its own. A bundled file has none of
 * those problems and catches the passwords that matter.
 */
@Documented
@Constraint(validatedBy = GuessablePasswordValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface GuessablePassword {

    String message() default "That password is too easy to guess. Try a longer phrase of your own.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
