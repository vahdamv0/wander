package com.wander;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.wander.common.PublicEndpoint;

/**
 * The gate that stays on.
 *
 * Walks every mapped handler and fires an anonymous request at it. Anything not
 * marked @PublicEndpoint must answer 401 or 403 — so forgetting to secure a new
 * endpoint fails the build instead of shipping. This is behavioural, not a
 * config review: it catches an endpoint left open by a matcher typo just as
 * readily as one nobody thought about.
 */
class EndpointAuthRatchetTest extends IntegrationTestBase {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyNonPublicEndpointRejectsAnonymousCallers() {
        List<String> reachable = new ArrayList<>();

        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            // Only handlers this project declares. springdoc's own controllers
            // are deliberately out of scope; everything under com.wander is in,
            // whatever path it is mapped to, so a new endpoint outside /api/**
            // cannot slip past the broad SPA permit in SecurityConfig.
            if (!handler.getBeanType().getPackageName().startsWith("com.wander") || isPublic(handler)) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                for (HttpMethod method : methodsOf(info)) {
                    String url = pattern.replaceAll("\\{[^}]+}", "1");
                    int status = statusOf(url, method);
                    // 401 unauthenticated, 403 rejected by CSRF before auth ran.
                    // Either way the caller got nowhere, which is the point.
                    if (status != 401 && status != 403) {
                        reachable.add("%s %s -> %d".formatted(method, url, status));
                    }
                }
            }
        });

        assertThat(reachable)
                .as("endpoints reachable without a session and not annotated @PublicEndpoint")
                .isEmpty();
    }

    private int statusOf(String url, HttpMethod method) {
        return http().method(method)
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                // An empty JSON body gets past body binding, so the status
                // reflects the security decision and not a parse failure.
                .body("{}")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    private static boolean isPublic(HandlerMethod handler) {
        return handler.hasMethodAnnotation(PublicEndpoint.class)
                || handler.getBeanType().isAnnotationPresent(PublicEndpoint.class);
    }

    private static List<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatterns().stream().map(Object::toString).toList();
        }
        return List.copyOf(info.getPatternValues());
    }

    private static List<HttpMethod> methodsOf(RequestMappingInfo info) {
        var declared = info.getMethodsCondition().getMethods();
        if (declared.isEmpty()) {
            return List.of(HttpMethod.GET);
        }
        return declared.stream().map(m -> HttpMethod.valueOf(m.name())).toList();
    }
}
