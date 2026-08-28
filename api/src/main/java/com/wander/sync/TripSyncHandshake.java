package com.wander.sync;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.wander.security.WanderUser;
import com.wander.trip.TripAccessService;

/**
 * The socket's own front door.
 *
 * This exists because **`EndpointAuthRatchetTest` cannot see a WebSocket
 * handler.** That test walks `RequestMappingHandlerMapping` and fires anonymous
 * requests at every handler it finds; a handler registered through
 * `WebSocketConfigurer` is not a `@RequestMapping`, so it is invisible to the
 * ratchet. Without the check below, `/api/ws/trips/{id}` would be a way around
 * the entire trip access model — and nothing in the build would say so. Hence
 * also `TripSyncIntegrationTest`, which is the ratchet for this one endpoint.
 *
 * Membership is resolved once, here, and never again for the life of the socket.
 * That is safe only because losing access closes the socket: see
 * `TripChange.revokedUserId` and `TripSyncHandler.broadcast`.
 */
@Component
public class TripSyncHandshake implements HandshakeInterceptor {

    static final String TRIP_ID = "wander.tripId";
    static final String USER_ID = "wander.userId";

    private final TripAccessService access;

    public TripSyncHandshake(TripAccessService access) {
        this.access = access;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {

        Long tripId = tripIdFromPath(request.getURI().getPath());
        if (tripId == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        // Belt and braces: SecurityConfig already authenticates /api/**, so an
        // anonymous handshake is refused by the filter chain before it gets here.
        // Repeating it means a change to that matcher cannot quietly open this.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof WanderUser user)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            // The same gate as every REST endpoint: a non-member is refused, and
            // a viewer is allowed — reading is what a socket does.
            access.requireMember(tripId, user.id());
        } catch (RuntimeException ex) {
            // One status for "no such trip" and "not yours", as everywhere else:
            // a handshake that distinguished them would be a trip-counting oracle
            // that the REST API deliberately is not.
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        attributes.put(TRIP_ID, tripId);
        attributes.put(USER_ID, user.id());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {
        // Nothing to do; the handler registers the session itself.
    }

    static Long tripIdOf(WebSocketSession session) {
        return (Long) session.getAttributes().get(TRIP_ID);
    }

    static Long userIdOf(WebSocketSession session) {
        return (Long) session.getAttributes().get(USER_ID);
    }

    /** The trailing path segment of /api/ws/trips/{tripId}, or null if it is not a number. */
    private static Long tripIdFromPath(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            return null;
        }
        try {
            return Long.valueOf(path.substring(slash + 1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
