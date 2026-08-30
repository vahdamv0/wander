package com.wander.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.config.WanderProperties;
import com.wander.trip.TripInviteService;
import com.wander.user.GlobalRole;
import com.wander.user.User;
import com.wander.user.UserRepository;

@Service
public class UserAccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final WanderProperties properties;
    private final TripInviteService invites;

    public UserAccountService(UserRepository users, PasswordEncoder passwordEncoder, WanderProperties properties,
            TripInviteService invites) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        // Registration reaching into invitations looks like a layering mistake
        // and is not: with self-signup off, an invitation is the *only* thing
        // that can authorise an account, so this is the authorisation check
        // rather than a favour done for another feature.
        this.invites = invites;
    }

    /**
     * A new account, from the sign-up form.
     *
     * Two doors, and an instance usually has exactly one of them open. Self-signup
     * is the switch; an invitation token is the other, and it works whether or not
     * the switch is on — otherwise turning sign-ups off would silently break every
     * invitation link, since accepting one requires an account and nothing here
     * sends mail to create one another way.
     *
     * The token is checked but **not spent**: joining the trip is a separate call
     * that locks the row and re-checks it. So this can at worst leave an account
     * with no trip, which is the failure worth having — the alternative burns the
     * invitation on the way to a registration that might still fail on a taken
     * email address.
     */
    @Transactional
    public User register(String email, String displayName, String rawPassword, String inviteToken) {
        if (!properties.registrationEnabled() && !invites.admits(inviteToken)) {
            // Fail closed: a disabled switch refuses rather than quietly
            // creating the account anyway. A missing token, a made-up one and a
            // spent one are all the same answer, so the endpoint cannot be used
            // to find out which invitations exist.
            throw new RegistrationDisabledException();
        }
        return create(email, displayName, rawPassword, GlobalRole.USER);
    }

    @Transactional
    public User create(String email, String displayName, String rawPassword, GlobalRole role) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyUsedException();
        }
        return users.save(new User(email, displayName, passwordEncoder.encode(rawPassword), role));
    }

    /**
     * Change a signed-in user's own password.
     *
     * There is no reset on this instance and there never will be while nothing
     * here sends mail, so this is the only way a password moves without somebody
     * editing {@code users.password_hash} by hand. That makes the current
     * password non-negotiable: it is the one thing a stolen session does not
     * carry, and without it a borrowed browser could lock the owner out of their
     * own account permanently.
     *
     * The stored hash is read from the database rather than taken from the
     * principal, which is a copy made when the session began and would still
     * hold the old hash after a change made elsewhere.
     */
    @Transactional
    public User changePassword(Long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(IncorrectPasswordException::new);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IncorrectPasswordException();
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            // Refused rather than accepted as a no-op: somebody typing their old
            // password into both boxes has misunderstood what they are doing,
            // and answering "done" would leave them believing the password
            // changed.
            throw new SamePasswordException();
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        return user;
    }

    public boolean hasAnyUser() {
        return users.count() > 0;
    }
}
