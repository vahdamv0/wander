package com.wander.sync;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.ObjectMapper;

/**
 * The open sockets, grouped by trip, and the one place that writes to them.
 *
 * Three things here are not obvious:
 *
 *  - **Every session is wrapped in a {@link ConcurrentWebSocketSessionDecorator}.**
 *    A {@code WebSocketSession} is not safe for concurrent sends, and broadcasts
 *    arrive on whatever thread committed the transaction — two people saving at
 *    once is the normal case, not the rare one. Without the decorator that
 *    corrupts the frame stream rather than failing cleanly.
 *  - **A send that fails closes and forgets the session.** A half-dead socket
 *    that nobody removes is a slow leak of both memory and send attempts.
 *  - **Incoming messages are ignored.** The client is allowed to send keepalive
 *    text through an idle proxy, and nothing it could say would be trusted
 *    anyway — every write goes through the authenticated REST endpoints, which
 *    is what keeps one access model rather than two.
 */
@Component
public class TripSyncHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TripSyncHandler.class);

    /** How much outbound may queue on one slow socket before it is dropped. */
    private static final int SEND_BUFFER_LIMIT = 64 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    private final Map<Long, Set<WebSocketSession>> byTrip = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public TripSyncHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long tripId = TripSyncHandshake.tripIdOf(session);
        if (tripId == null) {
            // The handshake interceptor is what puts it there, so this cannot
            // happen without a wiring mistake — but a socket with no trip would
            // otherwise sit in the map under a null key and receive nothing.
            close(session, CloseStatus.SERVER_ERROR);
            return;
        }
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
                SEND_BUFFER_LIMIT);
        byTrip.computeIfAbsent(tripId, key -> ConcurrentHashMap.newKeySet()).add(safe);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long tripId = TripSyncHandshake.tripIdOf(session);
        if (tripId != null) {
            forget(tripId, session);
        }
    }

    /**
     * Fans one change out to everybody watching that trip, including the person
     * who caused it — the client filters its own echo, because the server cannot
     * tell one of somebody's two open tabs from the other.
     */
    void broadcast(TripChange change) {
        Set<WebSocketSession> watchers = byTrip.get(change.tripId());
        if (watchers == null || watchers.isEmpty()) {
            return;
        }

        TextMessage message = new TextMessage(json.writeValueAsString(change));
        for (WebSocketSession session : watchers) {
            if (!session.isOpen()) {
                forget(change.tripId(), session);
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (IOException | IllegalStateException ex) {
                // IllegalStateException is the decorator's way of saying the send
                // buffer overflowed, i.e. this client stopped reading.
                log.debug("Dropping a trip {} watcher: {}", change.tripId(), ex.toString());
                forget(change.tripId(), session);
                close(session, CloseStatus.SESSION_NOT_RELIABLE);
            }
        }

        // Somebody lost access, or the trip is gone: hang up rather than leave a
        // socket open that may no longer be told anything. The event above is
        // what tells them why, so it is sent first.
        if (change.kind() == TripChangeKind.TRIP_DELETED) {
            closeAll(change.tripId(), CloseStatus.NORMAL);
        } else if (change.revokedUserId() != null) {
            closeFor(change.tripId(), change.revokedUserId());
        }
    }

    /** Open sockets on one trip. Test seam, and the reason the map is not private state. */
    int watcherCount(Long tripId) {
        Set<WebSocketSession> watchers = byTrip.get(tripId);
        return watchers == null ? 0 : watchers.size();
    }

    private void closeFor(Long tripId, Long userId) {
        Set<WebSocketSession> watchers = byTrip.get(tripId);
        if (watchers == null) {
            return;
        }
        for (WebSocketSession session : watchers) {
            if (userId.equals(TripSyncHandshake.userIdOf(session))) {
                forget(tripId, session);
                close(session, CloseStatus.NORMAL);
            }
        }
    }

    private void closeAll(Long tripId, CloseStatus status) {
        Set<WebSocketSession> watchers = byTrip.remove(tripId);
        if (watchers != null) {
            watchers.forEach(session -> close(session, status));
        }
    }

    private void forget(Long tripId, WebSocketSession session) {
        byTrip.computeIfPresent(tripId, (key, watchers) -> {
            // The set holds decorators, which do not equal the raw session the
            // close callback hands us — match on the id instead.
            watchers.removeIf(candidate -> candidate.getId().equals(session.getId()));
            return watchers.isEmpty() ? null : watchers;
        });
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // Already gone, which is the outcome we wanted.
        }
    }
}
