package com.wander.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.wander.user.GlobalRole;
import com.wander.user.User;

/**
 * The authenticated principal. Carries the user id so a controller never has to
 * re-query by email just to learn who is calling.
 *
 * `Serializable` is load-bearing, not decoration: sessions are stored in Postgres
 * (Spring Session JDBC), and the security context inside one is written with Java
 * serialization. Without this the *first* request after signing in fails, because
 * the principal cannot be written to the store — and it fails at runtime, in a
 * way no compiler warns about. `SessionPersistenceIntegrationTest` is what keeps
 * it honest.
 */
public record WanderUser(Long id, String email, String displayName, String passwordHash, GlobalRole role)
        implements UserDetails, Serializable {

    public static WanderUser from(User user) {
        return new WanderUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash(),
                user.getRole());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
