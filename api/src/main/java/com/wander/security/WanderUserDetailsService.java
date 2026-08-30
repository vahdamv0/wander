package com.wander.security;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.wander.user.User;
import com.wander.user.UserRepository;

@Service
public class WanderUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public WanderUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user for " + email));

        // A disabled account is refused here rather than through UserDetails'
        // own `isEnabled()`, and the reason is the session store. WanderUser is
        // Java-serialized into Postgres by Spring Session, so adding a component
        // to that record changes the serialized shape and every session written
        // by the previous version fails to deserialize on the first request after
        // an upgrade — signing everybody out to ship a feature about accounts.
        // Throwing here is an AuthenticationException like any other, so the
        // caller gets the same bare 401 a wrong password gets.
        //
        // This only closes the *next* sign-in. An account with a live session
        // would sail past it until the session expired, which is why disabling
        // also deletes the sessions and hangs up the sockets — see AdminService.
        if (user.isDisabled()) {
            throw new DisabledException("Account disabled");
        }
        return WanderUser.from(user);
    }
}
