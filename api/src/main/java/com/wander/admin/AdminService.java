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
 * Two powers, and both are deliberately narrow:
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
