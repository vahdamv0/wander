package com.wander.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.config.WanderProperties;
import com.wander.user.GlobalRole;
import com.wander.user.User;
import com.wander.user.UserRepository;

@Service
public class UserAccountService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final WanderProperties properties;

    public UserAccountService(UserRepository users, PasswordEncoder passwordEncoder, WanderProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Transactional
    public User register(String email, String displayName, String rawPassword) {
        if (!properties.registrationEnabled()) {
            // Fail closed: a disabled switch refuses rather than quietly
            // creating the account anyway.
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

    public boolean hasAnyUser() {
        return users.count() > 0;
    }
}
