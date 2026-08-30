package com.wander.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Minting and digesting the bearer tokens that arrive in a URL.
 *
 * Two features use one of these — an invitation link and a password reset link —
 * and they must agree about the shape, because both store a digest and look a
 * row up by it. Two copies of this would be two places for the encoding to
 * drift, and drift here does not fail loudly: it produces a token that hashes to
 * nothing, so every link minted after the change is a 404 that looks exactly
 * like a made-up one.
 *
 * **SHA-256, not bcrypt**, and it is the opposite reasoning to a password hash.
 * Bcrypt is slow on purpose because a password is short and guessable. These are
 * 256 bits from a CSPRNG: there is nothing to slow an attacker down about, and a
 * per-row salt would make the lookup — which must find a row *by* the presented
 * token — a scan of every row in the table instead of one indexed read.
 */
public final class SecureToken {

    /** 256 bits. Long enough that guessing is not a threat model. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureToken() {
    }

    public static String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe and unpadded: the token is a path segment, so '+' and '/'
        // would need escaping and '=' invites something in the chain to trim it.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256, hex. The only form of a token that is ever written down. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // Every JVM ships SHA-256; this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
