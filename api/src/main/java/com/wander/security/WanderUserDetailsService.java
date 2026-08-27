package com.wander.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.wander.user.UserRepository;

@Service
public class WanderUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public WanderUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return users.findByEmailIgnoreCase(email)
                .map(WanderUser::from)
                .orElseThrow(() -> new UsernameNotFoundException("No user for " + email));
    }
}
