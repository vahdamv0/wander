package com.wander.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler as deliberately reachable without a session.
 *
 * This is documentation with teeth: EndpointAuthRatchetTest walks every mapped
 * handler and fires an anonymous request at it, and anything not annotated here
 * must answer 401/403. Forgetting a guard is therefore a failing test rather
 * than an open endpoint, and opening one up has to be an explicit edit that
 * shows in review.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
}
