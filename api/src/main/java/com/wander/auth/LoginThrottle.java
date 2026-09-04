package com.wander.auth;

import java.time.Duration;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.wander.common.AttemptCounter;
import com.wander.common.RateLimitedException;
import com.wander.config.WanderProperties;

/**
 * How many times a credential may be guessed, and how many accounts may be made,
 * before this instance stops listening.
 *
 * `/api/auth/login` is the one endpoint on an internet-facing instance that gets
 * probed without anybody choosing to attack it in particular, and bcrypt alone is
 * not an answer: it makes each guess expensive for the *server*, which is a
 * denial of service as much as a defence.
 *
 * Two counters on the guessing side, and both matter because they fail
 * differently:
 *
 *  - **by email**, so one account cannot be ground through a word list from a
 *    thousand addresses;
 *  - **by client address**, so a spray across many accounts from one place is
 *    stopped even though no single account ever reaches its own limit. Its limit
 *    is the looser of the two, because a household, an office or a phone network
 *    is one address to us and several people signing in.
 *
 * A *successful* sign-in clears both, so the only thing that accumulates is
 * failure. That is what keeps this off the path of anybody using the application
 * normally: fat-fingering a password twice and then getting it right leaves no
 * trace at all.
 *
 * **Registration is counted differently, and on purpose.** There, every call
 * costs a bcrypt round and leaves a row behind whether it succeeds or not, so it
 * is *attempts* that are counted rather than failures, and a success clears
 * nothing. It lives in this class rather than in one of its own because the
 * argument is the same argument — an unmetered endpoint that hashes a password
 * for an anonymous caller — and two counters with two windows would be two places
 * to get the arithmetic wrong. Only the address is keyed: an account that does
 * not exist yet has no email worth remembering.
 *
 * The per-address counters are only worth anything because the proxy overwrites
 * `X-Forwarded-For` — see {@code AuthController.clientAddress} and the Caddyfile.
 *
 * What this deliberately does not do is lock an account. A lockout that outlives
 * the window turns a guessing attempt into a way of keeping the real owner out —
 * and with no password reset on this instance (see README), being locked out is
 * not recoverable without the operator.
 */
@Component
public class LoginThrottle {

    private final int maxPerEmail;
    private final int maxPerAddress;
    private final int maxRegistrationsPerAddress;
    private final int maxResetRequestsPerAddress;
    private final AttemptCounter counter;

    // Explicit, because the second constructor below means there is a choice to
    // make and Spring will not guess between two.
    @Autowired
    public LoginThrottle(WanderProperties properties) {
        this(properties.login().maxFailuresPerEmail(), properties.login().maxFailuresPerAddress(),
                properties.login().maxRegistrationsPerAddress(), properties.mail().maxRequestsPerAddress(),
                Duration.ofMinutes(properties.login().windowMinutes()), properties.login().trackedKeys());
    }

    /** Takes the window as a Duration so a test can use one measured in milliseconds. */
    LoginThrottle(int maxPerEmail, int maxPerAddress, int maxRegistrationsPerAddress,
            int maxResetRequestsPerAddress, Duration window, int trackedKeys) {
        this.maxPerEmail = maxPerEmail;
        this.maxPerAddress = maxPerAddress;
        this.maxRegistrationsPerAddress = maxRegistrationsPerAddress;
        this.maxResetRequestsPerAddress = maxResetRequestsPerAddress;
        this.counter = new AttemptCounter(window, trackedKeys);
    }

    /**
     * Called before the password is checked at all — the point is to not spend
     * the hash.
     *
     * @throws RateLimitedException when either counter is spent
     */
    public void check(String email, String address) {
        if (counter.spent(emailKey(email), maxPerEmail) || counter.spent(addressKey(address), maxPerAddress)) {
            // Deliberately the same message either way, and the same one an
            // unknown address gets: which counter ran out is a fact about who has
            // an account here.
            throw new RateLimitedException("Too many sign-in attempts. Wait a few minutes and try again.");
        }
    }

    /** A wrong password. Counts against the account and against where it came from. */
    public void failed(String email, String address) {
        counter.record(emailKey(email));
        counter.record(addressKey(address));
    }

    /** The right password. Both counters go, so a normal day never accumulates. */
    public void succeeded(String email, String address) {
        counter.forget(emailKey(email));
        counter.forget(addressKey(address));
    }

    /**
     * Called before an account is created — again, before the bcrypt round.
     *
     * @throws RateLimitedException when this address has registered too often
     */
    public void checkRegistration(String address) {
        if (counter.spent(registrationKey(address), maxRegistrationsPerAddress)) {
            throw new RateLimitedException("Too many accounts created from here. Wait a few minutes and try again.");
        }
    }

    /**
     * One registration attempt from this address, counted whether it worked or
     * not: a refused one still cost a lookup, and a succeeded one still made an
     * account. Nothing clears this but the window passing.
     */
    public void registrationAttempted(String address) {
        counter.record(registrationKey(address));
    }

    /**
     * Called before a reset link is looked at or redeemed.
     *
     * Keyed by address alone — a reset link carries no email address, which is
     * rather the point of it — and bounded by the same limit as a run of wrong
     * passwords, because that is what presenting a bad token is. Two things are
     * being metered at once and they want the same number: guessing at links
     * (hopeless against 256 bits, but it should still cost something), and the
     * bcrypt round the redeem call spends encoding a new password for an
     * anonymous caller.
     *
     * @throws RateLimitedException when this address has presented too many bad links
     */
    public void checkReset(String address) {
        if (counter.spent(resetKey(address), maxPerAddress)) {
            throw new RateLimitedException("Too many attempts. Wait a few minutes and try again.");
        }
    }

    /** A link that does not exist. Counted; an expired or spent one is not, because it was real. */
    public void resetFailed(String address) {
        counter.record(resetKey(address));
    }

    /** A password actually set. Clears the counter, exactly as a successful sign-in does. */
    public void resetSucceeded(String address) {
        counter.forget(resetKey(address));
    }

    /**
     * Called before a self-service reset link is asked for.
     *
     * This is the tightest limit in the class, and it is the only one that meters
     * an endpoint which makes something leave the building. Every call sends a
     * message on a relay somebody signed up for, addressed to a mailbox belonging
     * to a real person, so an unmetered version is not merely a way to spend this
     * box's CPU — it is a way to use this instance to post junk at a third party
     * and to get its sending domain listed for it.
     *
     * *Attempts*, like registration rather than like sign-in: a request that
     * found no account still cost a lookup, and one that worked still sent mail.
     * Nothing clears it but the window passing, because a success here is not
     * evidence of anything — the caller has not proved they own the address.
     *
     * Keyed by address alone. The email would be the better key and cannot be
     * used: keying on it would make the counter a record of which addresses have
     * been asked about, which is the fact this whole endpoint is written to keep
     * quiet.
     *
     * @throws RateLimitedException when this address has asked too often
     */
    public void checkResetRequest(String address) {
        if (counter.spent(resetRequestKey(address), maxResetRequestsPerAddress)) {
            throw new RateLimitedException("Too many requests. Wait a few minutes and try again.");
        }
    }

    /** One request from this address, counted whether or not it found an account. */
    public void resetRequested(String address) {
        counter.record(resetRequestKey(address));
    }

    /** For the test that holds the bound on the map. */
    int trackedKeyCount() {
        return counter.trackedKeyCount();
    }

    /**
     * Lowercased, because the login itself is case-insensitive on email — without
     * this, alternating the capitals is a way to start again with a fresh count.
     */
    private static String emailKey(String email) {
        return "e:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "a:" + (address == null ? "" : address);
    }

    /** Its own namespace, so a run of bad passwords does not also block signing up. */
    private static String registrationKey(String address) {
        return "r:" + (address == null ? "" : address);
    }

    /** And its own again, so a spent reset counter does not lock the household out of signing in. */
    private static String resetKey(String address) {
        return "p:" + (address == null ? "" : address);
    }

    /**
     * Separate from {@code resetKey}, so asking for links too often does not also
     * block redeeming one. They are opposite ends of the same journey and the
     * person at the second end may be somebody else entirely — a shared office
     * address where one person requested five links should not stop a colleague
     * finishing theirs.
     */
    private static String resetRequestKey(String address) {
        return "q:" + (address == null ? "" : address);
    }
}
