package com.wander.sync;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * One socket, one trip: `/api/ws/trips/{tripId}`.
 *
 * A path per trip rather than one socket carrying a trip id in every message.
 * The subscription is then the connection, which means membership is checked
 * once at the handshake by the container's own machinery instead of on every
 * frame by ours — and a client cannot ask for a trip it did not connect to.
 *
 * No SockJS fallback and no STOMP. Every browser this application supports has
 * had native WebSocket for a decade, the client sends nothing, and the payload
 * is one invalidation event: a broker and a sub-protocol would be machinery with
 * nothing to carry. Under `/api/` on purpose, so `SecurityConfig`'s default-deny
 * covers the handshake rather than the SPA's permissive GET rule.
 *
 * Allowed origins are left at Spring's default of same-origin only. The Angular
 * app is served from this very jar, so it has no reason to be widened — and
 * widening it would let any page on the internet open an authenticated socket
 * with the viewer's cookie.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TripSyncHandler handler;
    private final TripSyncHandshake handshake;

    public WebSocketConfig(TripSyncHandler handler, TripSyncHandshake handshake) {
        this.handler = handler;
        this.handshake = handshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/ws/trips/*").addInterceptors(handshake);
    }
}
