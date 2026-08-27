package com.wander.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Instance configuration. Every field is env-var settable so an operator can run
 * the container without editing a file — see compose.yaml.
 */
@ConfigurationProperties("wander")
public record WanderProperties(

        /** Self-signup. Off means an admin creates accounts (invites land in a later milestone). */
        @DefaultValue("true") boolean registrationEnabled,

        @DefaultValue Admin admin) {

    /**
     * First-boot admin. Both blank means the account is still created, with a
     * generated password printed once to the log — the operator never has to
     * hand-edit the database to get in.
     */
    public record Admin(@DefaultValue("") String email, @DefaultValue("") String password) {
    }
}
