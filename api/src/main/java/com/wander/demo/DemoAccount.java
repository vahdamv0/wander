package com.wander.demo;

import org.springframework.stereotype.Component;

import com.wander.config.WanderProperties;

/**
 * Whether an account is the *published* demo visitor.
 *
 * On a demo instance one account's password is printed on the sign-in page, so
 * everybody looking at the instance is signed in as the same person. That makes
 * a handful of ordinary, correct behaviours into accidents waiting to happen:
 * the first of them was **leaving the demo trip**, which any member may do to
 * themselves and which took the trip away from every visitor after them until
 * the container was restarted.
 *
 * Identified by the configured email rather than a column on `users`, because
 * that is already the only thing that makes this account special — {@code
 * DemoSeeder} looks it up the same way, and the sign-in page publishes it. Point
 * {@code wander.demo.email} somewhere else and the old account stops being
 * protected, which is right: it also stops being published, so it is no longer
 * the shared one. Answers false for every account when the demo is off, so an
 * ordinary instance behaves as it always did.
 */
@Component
public class DemoAccount {

    private final WanderProperties properties;

    public DemoAccount(WanderProperties properties) {
        this.properties = properties;
    }

    public boolean isPublishedVisitor(String email) {
        WanderProperties.Demo demo = properties.demo();
        return demo.enabled() && email != null && email.equalsIgnoreCase(demo.email());
    }
}
