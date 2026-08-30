package com.wander.trip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.common.ConflictException;
import com.wander.common.NotFoundException;
import com.wander.sync.TripChanges;
import com.wander.trip.dto.CreateInviteRequest;
import com.wander.trip.dto.CreatedInviteView;
import com.wander.trip.dto.InvitePreview;
import com.wander.trip.dto.TripInviteView;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * Invitation links: the last piece of sharing, and the only one that works for
 * somebody with no account here.
 *
 * `TripMemberService.add` needs an existing account because it works by email
 * address, and this instance sends no mail. A link needs none: the owner creates
 * it and delivers it themselves. That was always the way round the original
 * blocker — delivery was never this application's problem.
 *
 * Three rules, and the first is the one everything else rests on:
 *
 *  - **The token is never stored.** A SHA-256 digest is, and the token exists in
 *    exactly one response, once. The nightly dumps leave this machine, so a token
 *    at rest would turn a mislaid backup into working keys to other people's
 *    trips. It is the same bargain as the admin password printed once to the log.
 *  - **Accepting is a single transaction that re-checks everything.** A link that
 *    was usable when it was previewed may have been revoked, used or expired by
 *    the time it is accepted, and two people may click the same link at the same
 *    instant. The row is locked, checked and marked in one go.
 *  - **A bad token is a 404, always**, whatever is wrong with it. Distinguishing
 *    "no such invitation" from "that one expired" tells an anonymous guesser
 *    which of their attempts found something real.
 */
@Service
public class TripInviteService {

    /** 256 bits, URL-safe. Long enough that guessing is not a threat model. */
    private static final int TOKEN_BYTES = 32;

    private final TripInviteRepository invites;
    private final TripMemberRepository members;
    private final UserRepository users;
    private final TripAccessService access;
    private final TripChanges changes;
    private final SecureRandom random = new SecureRandom();

    public TripInviteService(TripInviteRepository invites, TripMemberRepository members, UserRepository users,
            TripAccessService access, TripChanges changes) {
        this.invites = invites;
        this.members = members;
        this.users = users;
        this.access = access;
        this.changes = changes;
    }

    /** Only the owner, exactly as with adding a member by email. */
    @Transactional
    public CreatedInviteView create(Long userId, Long tripId, CreateInviteRequest request) {
        TripMember owner = access.requireRole(tripId, userId, TripRole.OWNER);
        if (request.role() == TripRole.OWNER) {
            throw new IllegalArgumentException(
                    "A trip has one owner; use the role endpoint to transfer ownership");
        }

        String token = mintToken();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(request.expiresInDaysOrDefault()));
        TripInvite invite = invites.save(new TripInvite(owner.getTrip(), hash(token), request.role(),
                owner.getUser(), expiresAt));

        // The token travels in this response and nowhere else, ever.
        return new CreatedInviteView(TripInviteView.of(invite, Instant.now()), token, "/invite/" + token);
    }

    @Transactional(readOnly = true)
    public List<TripInviteView> list(Long userId, Long tripId) {
        access.requireRole(tripId, userId, TripRole.OWNER);
        Instant now = Instant.now();
        return invites.findAllByTripIdOrderByCreatedAtDesc(tripId).stream()
                .map(invite -> TripInviteView.of(invite, now))
                .toList();
    }

    @Transactional
    public void revoke(Long userId, Long tripId, Long inviteId) {
        // Authorised before the invitation is looked up, so a non-owner cannot
        // use the 404 to learn which invite ids exist — the same ordering
        // TripMemberService.remove uses for the same reason.
        access.requireRole(tripId, userId, TripRole.OWNER);
        TripInvite invite = invites.findByIdAndTripId(inviteId, tripId)
                .orElseThrow(() -> new NotFoundException("No such invitation"));
        if (invite.getRevokedAt() == null) {
            invite.revoke(Instant.now());
        }
    }

    /**
     * Whether this token is a live invitation — asked by registration, on an
     * instance where self-signup is off.
     *
     * It is the piece that keeps a closed instance from being a sealed one. The
     * journey has always been link -> register -> accept, and with sign-ups off
     * the middle step used to refuse, which left an invitation link that could
     * only ever be used by somebody who already had an account. Since nothing
     * here sends mail and there is no other way to create one, that would have
     * made "registration disabled" mean "nobody else can ever join".
     *
     * The invitation is **not spent here**. This admits somebody to the sign-up
     * form; `accept` is still what puts them on the trip and burns the link, and
     * it re-checks everything — so a token that expires between the two steps
     * costs an account, not a membership.
     *
     * Nothing is leaked by answering: a caller who guesses a live token can
     * register, which is exactly what a live token is *for*, and the tokens are
     * 256 bits from a CSPRNG. A wrong one is indistinguishable from sign-ups
     * simply being off, because the caller gets the same 403 either way.
     */
    @Transactional(readOnly = true)
    public boolean admits(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return invites.findByTokenHash(hash(token))
                .map(invite -> invite.isUsable(Instant.now()))
                .orElse(false);
    }

    /**
     * What the holder of a link is shown before they commit to it.
     *
     * Always answers for a token that exists, even an unusable one, because
     * "this invitation has expired, ask for another" is the message that lets
     * somebody do something about it. A token that does not exist is a 404.
     */
    @Transactional(readOnly = true)
    public InvitePreview preview(Long userId, String token) {
        TripInvite invite = invites.findByTokenHash(hash(token))
                .orElseThrow(() -> new NotFoundException("No such invitation"));

        Trip trip = invite.getTrip();
        String invitedBy = invite.getCreatedBy().getDisplayName();

        // Already on the trip: not an error, and not an invitation either. The
        // client sends them to the trip rather than showing a join button that
        // would fail.
        if (members.findByTripIdAndUserId(trip.getId(), userId).isPresent()) {
            return new InvitePreview(trip.getName(), invitedBy, invite.getRole(), false,
                    "You are already on this trip.", trip.getId());
        }

        Instant now = Instant.now();
        if (!invite.isUsable(now)) {
            return new InvitePreview(trip.getName(), invitedBy, invite.getRole(), false,
                    reasonFor(invite.statusAt(now)), null);
        }
        return new InvitePreview(trip.getName(), invitedBy, invite.getRole(), true, "", null);
    }

    /**
     * Joins the caller to the trip and burns the link.
     *
     * Everything is re-checked here rather than trusted from the preview: the two
     * calls are separated by however long somebody left the page open, and the
     * link may have been revoked or used in between. Two people accepting the
     * same link at the same moment resolve here too — both read the row, but the
     * transaction that commits second sees `accepted_at` already set and is
     * refused.
     */
    @Transactional
    public Long accept(Long userId, String token) {
        TripInvite invite = invites.findByTokenHashForUpdate(hash(token))
                .orElseThrow(() -> new NotFoundException("No such invitation"));

        Trip trip = invite.getTrip();
        Instant now = Instant.now();

        if (members.findByTripIdAndUserId(trip.getId(), userId).isPresent()) {
            // Idempotent in the way that matters: somebody who clicks the link
            // twice ends up on the trip both times rather than seeing an error.
            // The invitation is left alone — it was not this click that used it.
            return trip.getId();
        }
        if (!invite.isUsable(now)) {
            throw new ConflictException(reasonFor(invite.statusAt(now)));
        }

        User joining = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("No such user"));
        members.save(new TripMember(trip, joining, invite.getRole()));
        invite.acceptedBy(joining, now);

        // The trip page of everybody already watching gains a member.
        changes.membersChanged(trip.getId(), userId);
        return trip.getId();
    }

    private static String reasonFor(InviteStatus status) {
        return switch (status) {
            case ACCEPTED -> "This invitation has already been used.";
            case REVOKED -> "This invitation was revoked.";
            case EXPIRED -> "This invitation has expired.";
            case PENDING -> "";
        };
    }

    private String mintToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        // URL-safe and unpadded: the token is a path segment, so '+' and '/'
        // would need escaping and '=' invites something in the chain to trim it.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, hex. See V15 for why this is not bcrypt: the token is 256 bits of
     * CSPRNG output, so there is nothing to slow an attacker down about, and a
     * per-row salt would make the lookup a full scan of every invitation.
     */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // Every JVM ships SHA-256; this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
