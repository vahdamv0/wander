package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The live-sync socket.
 *
 * This test carries more weight than most, because **`EndpointAuthRatchetTest`
 * cannot see this endpoint.** The ratchet walks `RequestMappingHandlerMapping`,
 * and a handler registered through `WebSocketConfigurer` is not a
 * `@RequestMapping` — so the first two tests here are the only thing standing
 * between `/api/ws/trips/{id}` and a way around the whole trip access model.
 *
 * The rest asserts the property the feature exists for: what one member does
 * reaches another member's socket, and losing access hangs up.
 */
class TripSyncIntegrationTest extends IntegrationTestBase {

    private static final long TIMEOUT_SECONDS = 10;

    /** Collects what arrives, and notices when the server hangs up. */
    private static final class Recorder extends TextWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.countDown();
        }

        /** The next event, or a failure if the server stayed quiet. */
        String next() throws InterruptedException {
            String payload = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(payload).as("an event within %ds", TIMEOUT_SECONDS).isNotNull();
            return payload;
        }

        void expectClosed() throws InterruptedException {
            assertThat(closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the server closed the socket").isTrue();
        }

        void expectQuiet() throws InterruptedException {
            assertThat(messages.poll(1, TimeUnit.SECONDS)).as("no event at all").isNull();
        }
    }

    private WebSocketSession connect(Session session, Object tripId, Recorder recorder) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        // The session cookie is the credential here exactly as it is for REST;
        // the handshake is a GET, so no CSRF token is involved.
        headers.add(HttpHeaders.COOKIE, session.cookie());
        return new StandardWebSocketClient()
                .execute(recorder, headers, uriFor(tripId))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private URI uriFor(Object tripId) {
        return URI.create("ws://localhost:" + port + "/api/ws/trips/" + tripId);
    }

    private Object tripFor(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Split","destination":"Split","startDate":"2027-09-01","endDate":"2027-09-03"}
                """).getBody()).get("id");
    }

    private Object userIdOf(Session owner, Object tripId, Session member) {
        List<Map<String, Object>> members = asList(get(owner, "/api/trips/" + tripId + "/members").getBody());
        return members.stream()
                .filter(row -> member.email().equals(row.get("email")))
                .findFirst().orElseThrow().get("userId");
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    void anAnonymousHandshakeIsRefused() throws Exception {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        // No cookie at all: the filter chain refuses the handshake before the
        // interceptor even runs, and the client sees the upgrade fail.
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new Recorder(), new WebSocketHttpHeaders(), uriFor(tripId))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("an anonymous socket must not open")
                .isNotNull();
    }

    @Test
    void aNonMemberHandshakeIsRefused() throws Exception {
        Session owner = register("owner");
        Session stranger = register("stranger");
        Object tripId = tripFor(owner);

        assertThatThrownBy(() -> connect(stranger, tripId, new Recorder()))
                .as("a signed-in stranger must not open a socket on somebody else's trip")
                .isNotNull();
    }

    @Test
    void aMemberIsToldWhenSomebodyElseChangesTheItinerary() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);
        assertThat(post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email())).getStatusCode().value()).isEqualTo(201);
        long ownerId = asLong(userIdOf(owner, tripId, owner));

        Recorder watching = new Recorder();
        Recorder acting = new Recorder();
        WebSocketSession viewerSocket = connect(viewer, tripId, watching);
        WebSocketSession ownerSocket = connect(owner, tripId, acting);

        assertThat(post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-09-01","name":"Diocletian's Palace"}
                """).getStatusCode().value()).isEqualTo(201);

        Map<String, Object> event = asMap(watching.next());
        assertThat(event).containsEntry("kind", "ITINERARY");
        assertThat(asLong(event.get("tripId"))).isEqualTo(asLong(tripId));
        // Who did it, so a client that already re-read can ignore its own echo.
        assertThat(asLong(event.get("actorUserId"))).isEqualTo(ownerId);
        assertThat(event.get("revokedUserId")).isNull();

        // The actor's own socket is told as well: the server cannot tell one of
        // somebody's two open tabs from the other, so the filtering is the
        // client's job and this is what it filters.
        assertThat(asMap(acting.next())).containsEntry("kind", "ITINERARY");

        // A day note rides along on the itinerary, so it is the same kind of event.
        assertThat(put(owner, "/api/trips/" + tripId + "/days/2027-09-02/note", """
                {"note":"Ferry at seven"}
                """).getStatusCode().value()).isEqualTo(200);
        assertThat(asMap(watching.next())).containsEntry("kind", "ITINERARY");

        viewerSocket.close();
        ownerSocket.close();
    }

    @Test
    void anExpenseChangeIsItsOwnKindOfEvent() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email()));
        long ownerId = asLong(userIdOf(owner, tripId, owner));

        Recorder watching = new Recorder();
        WebSocketSession socket = connect(viewer, tripId, watching);

        assertThat(post(owner, "/api/trips/" + tripId + "/expenses", """
                {"description":"Ferry","amountMinor":4500,"spentOn":"2027-09-01",
                 "paidByUserId":%s,"splitMode":"EQUAL","shares":[{"userId":%s}]}
                """.formatted(ownerId, ownerId)).getStatusCode().value()).isEqualTo(201);

        // A separate kind from ITINERARY: the ledger and the day list are
        // different pages, and each should re-read only what changed.
        Map<String, Object> event = asMap(watching.next());
        assertThat(event).containsEntry("kind", "EXPENSES");
        assertThat(asLong(event.get("actorUserId"))).isEqualTo(ownerId);

        socket.close();
    }

    @Test
    void aPackingChangeIsItsOwnKindOfEvent() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email()));

        Recorder watching = new Recorder();
        WebSocketSession socket = connect(viewer, tripId, watching);

        assertThat(post(owner, "/api/trips/" + tripId + "/packing", """
                {"description":"Tent","assigneeUserId":null}
                """).getStatusCode().value()).isEqualTo(201);

        // Its own kind: the packing page and the day list are different pages, and
        // each should re-read only what changed.
        assertThat(asMap(watching.next())).containsEntry("kind", "PACKING");

        socket.close();
    }

    @Test
    void nothingIsSaidAboutAWriteThatWasRefused() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email()));

        Recorder watching = new Recorder();
        WebSocketSession socket = connect(viewer, tripId, watching);

        // A viewer's write is refused, and a day outside the trip's range is
        // rejected: neither reached the database, so neither is anybody's news.
        assertThat(post(viewer, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2027-09-01","name":"Not allowed"}
                """).getStatusCode().value()).isEqualTo(403);
        assertThat(post(owner, "/api/trips/" + tripId + "/places", """
                {"dayDate":"2028-01-01","name":"Outside the trip"}
                """).getStatusCode().value()).isEqualTo(400);

        watching.expectQuiet();
        socket.close();
    }

    @Test
    void losingAccessClosesTheSocket() throws Exception {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email()));
        Object viewerId = userIdOf(owner, tripId, viewer);

        Recorder watching = new Recorder();
        connect(viewer, tripId, watching);

        assertThat(delete(owner, "/api/trips/" + tripId + "/members/" + viewerId)
                .getStatusCode().value()).isEqualTo(204);

        // Told why, then hung up on: membership is resolved once at the handshake,
        // so a socket left open would keep being told about a trip its owner may
        // no longer read.
        Map<String, Object> event = asMap(watching.next());
        assertThat(event).containsEntry("kind", "MEMBERS");
        assertThat(asLong(event.get("revokedUserId"))).isEqualTo(asLong(viewerId));
        watching.expectClosed();

        // And it cannot be reopened.
        assertThatThrownBy(() -> connect(viewer, tripId, new Recorder())).isNotNull();
    }

    @Test
    void deletingTheTripClosesEverySocket() throws Exception {
        Session owner = register("owner");
        Session editor = register("editor");
        Object tripId = tripFor(owner);
        post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"EDITOR"}
                """.formatted(editor.email()));

        Recorder watching = new Recorder();
        connect(editor, tripId, watching);

        assertThat(delete(owner, "/api/trips/" + tripId).getStatusCode().value()).isEqualTo(204);

        assertThat(asMap(watching.next())).containsEntry("kind", "TRIP_DELETED");
        watching.expectClosed();
    }
}
