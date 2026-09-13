package com.wander.route;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.wander.common.UpstreamUnavailableException;
import com.wander.config.WanderProperties;
import com.wander.geo.RateGate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OSRM's table service over HTTP.
 *
 * OSRM is the routing engine because it is the one a self-hoster can actually
 * run: `docker run osrm/osrm-backend` over an extract of the country they are
 * going to, no account and no key. That is the same test the basemap and the
 * geocoder had to pass, and it is why {@code wander.routing.base-url} is a
 * setting rather than a constant — there is no public OSRM anybody is entitled
 * to lean on, which is also why this whole feature is **off by default**.
 *
 * Two details about the wire format are worth knowing before touching this:
 *
 *  - **Coordinates are longitude first.** They go into the path as
 *    `{lon},{lat};{lon},{lat}`, which is the opposite order to every other
 *    upstream here and to `places`. Getting it backwards does not fail — it
 *    routes somewhere else entirely, usually into the sea, and answers a
 *    perfectly well-formed matrix about it.
 *  - **A cell can be null.** A stop OSRM cannot snap to a road (an island, a
 *    pedestrian-only address with `driving`) comes back as `null`, not as a
 *    large number. Read as zero it would look like the nearest possible stop
 *    and the optimiser would happily route through it first, so the whole
 *    answer is refused instead.
 */
@Component
public class OsrmRouteClient implements RouteClient {

    private final RestClient http;
    private final ObjectMapper json;
    private final RateGate gate;

    public OsrmRouteClient(WanderProperties properties, ObjectMapper json, RateGate osrmGate) {
        this.json = json;
        this.gate = osrmGate;
        this.http = RestClient.builder()
                .baseUrl(properties.routing().baseUrl())
                .defaultHeader("User-Agent",
                        "wander/" + properties.version() + " (self-hosted travel planner)")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Accept-Encoding", "gzip")
                .requestFactory(timeouts())
                .build();
    }

    /**
     * Copied from the weather client, and deliberately not shared: a global
     * customiser would change every RestClient in the application. A routing
     * matrix is more work than a forecast, so the read timeout is longer.
     */
    private static JdkClientHttpRequestFactory timeouts() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    @Override
    public Matrix table(RouteProfile profile, List<Point> points) {
        gate.pass();
        String body;
        try {
            body = http.get()
                    .uri(uri -> tableUri(uri, profile, points))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException ex) {
            // Named rather than swallowed: unlike a forecast, there is nothing
            // useful to draw without this answer, and the caller asked for it.
            throw new UpstreamUnavailableException(
                    "The routing service could not be reached: " + ex.getMessage());
        }
        return parse(body, points.size());
    }

    private static URI tableUri(UriBuilder uri, RouteProfile profile, List<Point> points) {
        StringJoiner coordinates = new StringJoiner(";");
        for (Point point : points) {
            // Longitude first. See the class note.
            coordinates.add(point.longitude() + "," + point.latitude());
        }
        return uri.path("/table/v1/" + profile.segment() + "/" + coordinates)
                .queryParam("annotations", "duration,distance")
                .build();
    }

    /**
     * Package-private so the mapping — which is where the risk in this class
     * lives — can be tested without a routing engine.
     */
    Matrix parse(String body, int expected) {
        JsonNode root;
        try {
            root = json.readTree(body == null ? "" : body);
        } catch (RuntimeException ex) {
            throw new UpstreamUnavailableException(
                    "The routing service answered with something that is not JSON");
        }
        String code = root.path("code").asString("");
        if (!"Ok".equals(code)) {
            // OSRM says why in `message`, and it is usually worth passing on:
            // "NoSegment" means a stop is nowhere near a road this profile can
            // use, which is a fact about the itinerary rather than an outage.
            String message = root.path("message").asString("");
            throw new UpstreamUnavailableException("The routing service refused this day"
                    + (message.isBlank() ? "" : ": " + message));
        }
        return new Matrix(square(root.path("durations"), expected, "durations"),
                square(root.path("distances"), expected, "distances"));
    }

    private static long[][] square(JsonNode rows, int expected, String what) {
        if (!rows.isArray() || rows.size() != expected) {
            throw new UpstreamUnavailableException(
                    "The routing service answered with a " + what + " table of the wrong size");
        }
        long[][] matrix = new long[expected][expected];
        for (int i = 0; i < expected; i++) {
            JsonNode row = rows.get(i);
            if (!row.isArray() || row.size() != expected) {
                throw new UpstreamUnavailableException(
                        "The routing service answered with a ragged " + what + " table");
            }
            for (int j = 0; j < expected; j++) {
                JsonNode cell = row.get(j);
                if (cell == null || !cell.isNumber()) {
                    // A hole, not a zero. See the class note.
                    throw new UpstreamUnavailableException(
                            "The routing service could not reach one of these stops");
                }
                matrix[i][j] = Math.round(cell.asDouble());
            }
        }
        return matrix;
    }
}
