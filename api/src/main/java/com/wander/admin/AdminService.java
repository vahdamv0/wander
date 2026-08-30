package com.wander.admin;

import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.admin.dto.AdminUserView;
import com.wander.auth.AccountSessions;
import com.wander.common.ConflictException;
import com.wander.common.NotFoundException;
import com.wander.user.AccountDisabled;
import com.wander.user.GlobalRole;
import com.wander.user.User;
import com.wander.user.UserRepository;

/**
 * What an administrator can do to an account.
 *
 * {@code GlobalRole.ADMIN} has existed since V1 and, until this class, granted
 * nothing at all: the first-boot account was labelled an administrator and had
 * precisely the authority of everybody else. A role that grants nothing is worse
 * than no role, because it reads as a control that exists.
 *
 * Three powers, and each is deliberately narrow:
 *
 *  - **List the accounts.** Not their trips, not their itineraries. Being an
 *    administrator of this instance is authority over accounts, not a way to
 *    read other people's holidays — trip access still goes through
 *    {@code TripAccessService} for an admin exactly as it does for anybody, and
 *    nothing here is a back door into one.
 *  - **Take an account out of service, or put it back.** Not delete it: expenses
 *    and packing items reference their user and a departed member's shares are
 *    history the trip still needs, so a delete would silently forgive a debt on
 *    somebody else's trip. See {@code User.disable}.
 *  - **Make somebody else an administrator, or stop being one.** Without this the
 *    role could only ever be held by the account first boot created, and every
 *    rule here about *other* administrators described an unreachable state.
 *
 * Password resets are the third thing an administrator can do and they live in
 * {@link PasswordResetService}, because half of that feature is anonymous.
 */
@Service
public class AdminService {

    private final UserRepository users;
    private final AccountSessions sessions;
    private final ApplicationEventPublisher events;

    public AdminService(UserRepository users, AccountSessions sessions, ApplicationEventPublisher events) {
        this.users = users;
        this.sessions = sessions;
        this.events = events;
    }

    /**
     * Every account on the instance, newest first.
     *
     * Unpaginated, and that is a decision rather than an oversight: this is a
     * self-hosted planner for a household or a group of friends, so the list is
     * tens of rows. An instance where it is not has outgrown a page that shows
     * everybody at once, and paging it before then would be inventing a scroll
     * position nobody needs.
     */
    @Transactional(readOnly = true)
    public List<AdminUserView> listAccounts() {
        return users.findAllByOrderByCreatedAtDesc().stream().map(AdminUserView::of).toList();
    }

    /**
     * Promote an account to administrator, or demote one back.
     *
     * This is what makes "an instance has administrators" true rather than "an
     * instance has the account first boot made". Without it `GlobalRole.ADMIN`
     * could only ever be held by one account, created before anybody else
     * existed, and every rule written here about *other* administrators
     * described a state the code could not reach.
     *
     * **The sessions go, in both directions**, and the demotion case is why it
     * is not optional: the principal is serialised into the session at sign-in
     * and read back on every request, so somebody demoted would keep
     * `ROLE_ADMIN` until their session expired — authority removed in the
     * database and still held in fact, with nothing failing to say so. Promotion
     * shares the rule rather than being the exception, because one behaviour is
     * easier to reason about than two and the alternative is a person told they
     * are an administrator whose menu says otherwise until they sign out.
     *
     * **The last administrator cannot be demoted**, which is the "last owner"
     * check {@code TripMemberService} is pleased not to need. A trip's ownership
     * *moves* in one transaction, so there is never a moment with no owner. An
     * instance's administrators are a set, there is no equivalent atomic move,
     * and this count is the only thing between a demotion and an instance nobody
     * can administer. Stepping down is otherwise allowed, including on your own
     * account — promoting a successor and handing over is a real thing to want,
     * and refusing it outright would mean an instance can never change hands.
     */
    @Transactional
    public AdminUserView setRole(Long userId, GlobalRole role) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("No such account"));
        if (user.getRole() == role) {
            // Idempotent rather than an error: two admins clicking at once, or a
            // stale list, should not produce a failure over a state that is
            // already what was asked for. Nothing is ended, because nothing
            // changed — signing somebody out to confirm a no-op would be worse
            // than the no-op.
            return AdminUserView.of(user);
        }
        if (role == GlobalRole.USER && lastUsableAdmin(user)) {
            throw new ConflictException(
                    "This is the only administrator who can sign in. Promote somebody else first.");
        }

        user.changeRole(role);
        // Not endOthers: this is the caller's own session too when they step
        // down, and somebody who has just given up their authority should not
        // keep a page that still offers it.
        sessions.endAll(user.getEmail());
        return AdminUserView.of(user);
    }

    /**
     * Whether this account is the only administrator left who could actually use
     * the authority.
     *
     * Disabled admins are not counted, here or in {@code setDisabled}: an account
     * that cannot sign in cannot administer anything, so leaving one as the sole
     * administrator is the same lockout reached by a longer route.
     */
    private boolean lastUsableAdmin(User user) {
        return user.getRole() == GlobalRole.ADMIN
                && !user.isDisabled()
                && users.countByRoleAndDisabledAtIsNull(GlobalRole.ADMIN) <= 1;
    }

    /**
     * Take an account out of service, or put it back.
     *
     * Disabling has to do three things or it does nothing worth having. The row
     * is marked, which is what stops the next sign-in
     * ({@code WanderUserDetailsService}). Every session for the account is
     * deleted, because the session is the credential and a live one would sail
     * straight past that check for as long as it lasted. And an event goes out
     * so the WebSockets hang up — a socket resolves its membership once, at the
     * handshake, so one left open would keep being told about other people's
     * edits by an account that is no longer allowed to look.
     *
     * **An administrator may not disable themselves.** Not paternalism: the
     * account doing it would lose its own session mid-request, and on an instance
     * with one admin — which is every instance by default — there would then be
     * nobody left who could turn it back on. The recovery is a hand-edit in psql,
     * which is exactly the situation this whole feature exists to end. Disabling
     * *another* admin is allowed, because that is a real thing that has to be
     * possible and it leaves somebody holding the keys.
     *
     * There is deliberately **no last-administrator check here**, unlike
     * {@link #setRole}, and it is not an omission: the caller is an enabled
     * administrator by definition, and they cannot be the target, so an enabled
     * administrator always survives this call. Adding the check would be dead
     * code that reads as though it were guarding something.
     */
    @Transactional
    public AdminUserView setDisabled(Long adminId, Long userId, boolean disabled) {
        if (disabled && adminId.equals(userId)) {
            throw new ConflictException("You cannot disable your own account.");
        }
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("No such account"));

        if (disabled) {
            user.disable(Instant.now());
            // Both halves of "signed in", because there are two: the HTTP
            // session and any socket already open on it.
            sessions.endAll(user.getEmail());
            events.publishEvent(new AccountDisabled(user.getId()));
        } else {
            user.enable();
        }
        return AdminUserView.of(user);
    }
}
