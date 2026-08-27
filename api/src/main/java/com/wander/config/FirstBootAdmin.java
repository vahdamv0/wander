package com.wander.config;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wander.auth.UserAccountService;
import com.wander.user.GlobalRole;
import com.wander.user.User;

/**
 * Seeds an admin on an empty instance so a fresh container is reachable without
 * anyone hand-editing the database. If no credentials are configured the
 * password is generated and printed once — it is never stored in plaintext and
 * never printed again on later boots.
 */
@Configuration
public class FirstBootAdmin {

    private static final Logger log = LoggerFactory.getLogger(FirstBootAdmin.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Bean
    ApplicationRunner seedAdmin(UserAccountService accounts, WanderProperties properties) {
        return args -> {
            if (accounts.hasAnyUser()) {
                return;
            }
            String email = properties.admin().email().isBlank() ? "admin@wander.local"
                    : properties.admin().email();
            boolean generated = properties.admin().password().isBlank();
            String password = generated ? generatePassword() : properties.admin().password();

            User admin = accounts.create(email, "Administrator", password, GlobalRole.ADMIN);
            if (generated) {
                log.warn("""

                        ┌──────────────────────────────────────────────────────────────┐
                        │ wander created its first admin account                        │
                        │   email:    {}
                        │   password: {}
                        │ This password is shown once. Change it after logging in.      │
                        └──────────────────────────────────────────────────────────────┘
                        """, admin.getEmail(), password);
            } else {
                log.info("Created first admin account {} from configuration", admin.getEmail());
            }
        };
    }

    private static String generatePassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
