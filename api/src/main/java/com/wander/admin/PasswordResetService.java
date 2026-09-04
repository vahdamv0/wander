package com.wander.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.admin.dto.CreateResetRequest;
import com.wander.admin.dto.CreatedResetView;
import com.wander.admin.dto.PasswordResetView;
import com.wander.admin.dto.ResetPreview;
import com.wander.auth.AccountSessions;
import com.wander.auth.UserAccountService;
import com.wander.common.ConflictException;
import com.wander.common.NotFoundException;
import com.wander.common.SecureToken;
import com.wander.config.WanderProperties;
import com.wander.mail.MailClient;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * Putting a password back, without sending mail.
 *
 * README stated the limit plainly for as long as this application has existed:
 * there is no password reset, so a forgotten one is recovered by the operator
 * editing {@code users.password_hash} by hand. That was honest for a household
 * and is the wrong answer for an instance with people on it.
 *
 * The way round it is the one invitation links already found. A reset needs no
 * mail, because the administrator already has a way to talk to the person —
 * delivery was never this application's problem. So an admin mints a link, hands
 * it over however they like, and its holder sets a password.
 *
 * Four rules, and they are the invitation rules with the stakes raised, because
 * this token takes an *account* rather than admitting somebody to a trip:
 *
 *  - **The token is never stored.** A SHA-256 digest is, and the token exists in
 *    one response, once. The nightly dumps leave this machine.
 *  - **Redeeming locks the row and re-checks everything.** A link is previewed
 *    and redeemed in two calls separated by however long the page was open, and
 *    two requests can arrive together.
 *  - **A token that does not exist is a 404, always.** A token that *does* exist
 *    but is spent, revoked or expired answers with a reason, because its holder
 *    is not an attacker and "ask for another" is actionable.
 *  - **Redeeming ends every session for the account**, with no exception. The
 *    person setting the password may be recovering from exactly the situation
 *    where somebody else has one.
 */
@Service
public class PasswordResetService {

    /** What an anonymous holder is told, whatever is wrong with their token. */
    private static final String NOT_VALID = "This reset link is not valid.";

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final PasswordResetRepository resets;
    private final UserRepository users;
    private final UserAccountService accounts;
    private final AccountSessions sessions;
    private final MailClient mail;
    private final WanderProperties properties;

    public PasswordResetService(PasswordResetRepository resets, UserRepository users, UserAccountService accounts,
            AccountSessions sessions, MailClient mail, WanderProperties properties) {
        this.resets = resets;
        this.users = users;
        this.accounts = accounts;
        this.sessions = sessions;
        this.mail = mail;
        this.properties = properties;
    }

    /** Whether this instance can offer the self-service route at all. */
    public boolean selfServiceEnabled() {
        return mail.enabled();
    }

    /**
     * Somebody who cannot sign in, asking for a link themselves.
     *
     * <p><b>This method tells the caller nothing, and that is its entire
     * design.</b> Unknown address, disabled account, a relay that refused the
     * message — every path returns quietly, and the controller answers 204 to all
     * of them, including the ones where nothing happened. An endpoint that
     * answered "no such account" would be a way to test an address against this
     * instance without holding anything, and on a trip planner the membership
     * list is the private part: knowing that a particular person has an account
     * here is most of what an attacker wanted to learn.
     *
     * <p>That is also why the timing is not worth defending beyond this. A
     * missing account skips a token mint and an SMTP round trip, so a determined
     * caller can distinguish the two by clock. Closing that would mean sending
     * mail to nobody or sleeping a random interval, and both are worse than the
     * leak: the honest limit is that this stops casual enumeration, not a
     * patient adversary with a stopwatch.
     *
     * <p>The link is minted by the same {@code SecureToken} and stored the same
     * hashed way as an administrator's, so everything the redeem path already
     * checks — single use, expiry, revocation, the row lock — applies unchanged.
     * Only two things differ: it lives for minutes rather than days, because a
     * mailbox is a place a link sits around; and {@code createdBy} is the account
     * itself rather than an administrator, which is literally true and is what
     * lets the admin's list show who asked for a link without a nullable column
     * or a migration.
     */
    @Transactional
    public void requestReset(String email, String baseUrl) {
        if (!mail.enabled()) {
            // Should not be reachable — the controller 404s first — but a service
            // that would silently mint an undeliverable token if that check ever
            // moved is not one to leave lying around.
            return;
        }

        Optional<User> found = users.findByEmailIgnoreCase(email == null ? "" : email.trim());
        if (found.isEmpty()) {
            log.debug("Reset requested for an address with no account");
            return;
        }
        User user = found.get();
        if (user.isDisabled()) {
            // Deliberately silent, and it matches `create`, which refuses an
            // administrator outright. A live link must never be a way back into
            // an account somebody shut off on purpose — and saying so here would
            // tell an anonymous caller that the account exists.
            log.debug("Reset requested for a disabled account");
            return;
        }

        String token = SecureToken.mint();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(properties.mail().resetExpiresMinutes()));
        // createdBy is the user: they asked for it. See the note above.
        resets.save(new PasswordReset(user, SecureToken.hash(token), user, expiresAt));

        String link = baseUrl + "/reset/" + token;
        mail.send(user.getEmail(), "Reset your wander password", body(user.getDisplayName(), link));
    }

    /**
     * The message. Plain text, short, and it says what to do if it was not you.
     *
     * No marketing, no logo and exactly one link — partly because that is what
     * this is, and partly because a short plain message from a new sending domain
     * is the shape least likely to be filed as junk, which for this feature is
     * the difference between working and not.
     */
    private String body(String displayName, String link) {
        int minutes = properties.mail().resetExpiresMinutes();
        return """
                Hello %s,

                Somebody asked to reset the password on your wander account. If it was
                you, open this link and choose a new one:

                %s

                The link works once and expires in %d minutes.

                If it was not you, you do not need to do anything: your password has not
                changed, and the link cannot be used without this message.
                """.formatted(displayName, link, minutes);
    }

    /**
     * Mint a link for one account. Administrators only — see the controller.
     *
     * A previously minted link is **not** revoked here. Two outstanding links for
     * one account is a real situation: the first message did not arrive, or went
     * to an address the person no longer reads. Both work until one is used, and
     * the list is how an administrator revokes the one they no longer want.
     */
    @Transactional
    public CreatedResetView create(Long adminId, Long userId, CreateResetRequest request) {
        User target = users.findById(userId).orElseThrow(() -> new NotFoundException("No such account"));
        if (target.isDisabled()) {
            // Refused rather than allowed and then blocked at redemption: minting
            // a link that cannot possibly work, and handing it to somebody, is a
            // worse answer than saying so now. Enable the account first — which
            // is a decision, and should be made deliberately.
            throw new ConflictException("This account is disabled. Enable it before resetting its password.");
        }
        User admin = users.findById(adminId).orElseThrow(() -> new NotFoundException("No such account"));

        String token = SecureToken.mint();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(request.expiresInDaysOrDefault()));
        PasswordReset reset = resets.save(new PasswordReset(target, SecureToken.hash(token), admin, expiresAt));

        // The token travels in this response and nowhere else, ever.
        return new CreatedResetView(PasswordResetView.of(reset, Instant.now()), token, "/reset/" + token);
    }

    @Transactional(readOnly = true)
    public List<PasswordResetView> list(Long userId) {
        Instant now = Instant.now();
        return resets.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(reset -> PasswordResetView.of(reset, now))
                .toList();
    }

    @Transactional
    public void revoke(Long userId, Long resetId) {
        PasswordReset reset = resets.findByIdAndUserId(resetId, userId)
                .orElseThrow(() -> new NotFoundException("No such reset link"));
        if (reset.getRevokedAt() == null) {
            reset.revoke(Instant.now());
        }
    }

    /**
     * What the holder of a link is shown before they type a new password.
     *
     * Answers for a token that exists even when it is unusable, so somebody
     * holding a spent link is told to ask for another rather than being shown a
     * 404 that suggests they mistyped the URL. A token that does not exist gets
     * that 404, whatever is wrong with it, so a guesser never learns which
     * attempt found something real.
     */
    @Transactional(readOnly = true)
    public ResetPreview preview(String token) {
        PasswordReset reset = find(token);
        User user = reset.getUser();
        Instant now = Instant.now();

        if (user.isDisabled()) {
            // The account was disabled after the link was minted. Distinguished
            // from an expired link because it is a different thing to ask about:
            // there is no point requesting another link, and the administrator
            // has already been told.
            return new ResetPreview(user.getEmail(), user.getDisplayName(), false,
                    "This account has been disabled. Ask the administrator of this instance.");
        }
        if (!reset.isUsable(now)) {
            return new ResetPreview(user.getEmail(), user.getDisplayName(), false, reasonFor(reset.statusAt(now)));
        }
        return new ResetPreview(user.getEmail(), user.getDisplayName(), true, "");
    }

    /**
     * Sets the password and burns the link.
     *
     * Everything is re-checked here rather than trusted from the preview, under a
     * row lock. Two requests arriving together — a double-clicked button, a link
     * opened in two tabs — would otherwise both find an unused row, both set a
     * password, and the second would silently overwrite the first: whoever typed
     * the losing one cannot sign in and has no way to find out why.
     *
     * The sessions go last and they all go. Somebody using this is often
     * recovering an account they think somebody else has a session on, and
     * leaving those alive would make the new password worth very little.
     */
    @Transactional
    public void redeem(String token, String newPassword) {
        PasswordReset reset = resets.findByTokenHashForUpdate(SecureToken.hash(token))
                .orElseThrow(() -> new NotFoundException(NOT_VALID));

        User user = reset.getUser();
        Instant now = Instant.now();

        if (user.isDisabled()) {
            throw new ConflictException("This account has been disabled.");
        }
        if (!reset.isUsable(now)) {
            throw new ConflictException(reasonFor(reset.statusAt(now)));
        }

        accounts.setPassword(user, newPassword);
        reset.used(now);
        sessions.endAll(user.getEmail());
    }

    /**
     * The message is a sentence rather than the terse note a 404 usually carries,
     * because this is the one 404 in the application that a person reads.
     * Everywhere else the reader is a developer or a client with a fallback of
     * its own; here it is somebody anonymous holding a link that did not work,
     * and `messageOf` prefers the server's wording over the client's fallback.
     * "No such reset link" is a correct thing to say to the wrong audience.
     */
    private PasswordReset find(String token) {
        return resets.findByTokenHash(SecureToken.hash(token))
                .orElseThrow(() -> new NotFoundException(NOT_VALID));
    }

    /**
     * The whole sentence, advice included, rather than a fragment the page adds
     * a follow-up to.
     *
     * The follow-up is not the same in every case, which is what makes this the
     * server's job: "ask for another" is right for a link that was used, revoked
     * or expired, and wrong for an account that has been disabled — there is no
     * point asking for a link that cannot be minted. The page appended one line
     * to all of them and told somebody to do a useless thing, which is how this
     * ended up here.
     */
    private static String reasonFor(ResetStatus status) {
        String again = " Ask whoever sent it to you for another one.";
        return switch (status) {
            case USED -> "This link has already been used." + again;
            case REVOKED -> "This link was revoked." + again;
            case EXPIRED -> "This link has expired." + again;
            case PENDING -> "";
        };
    }
}
