package com.wander.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.wander.common.UpstreamUnavailableException;
import com.wander.config.WanderProperties;
import com.wander.geo.RateGate;

import tools.jackson.databind.json.JsonMapper;

/**
 * The mapping, without a routing engine — the same shape as
 * {@code OpenMeteoWeatherClientTest}, and for the same reason: parsing is where
 * this class can be wrong, and the network is where a test cannot go.
 *
 * The case that matters most is a `null` cell. OSRM answers `null` for a stop
 * it cannot snap to a road, and read as zero that stop becomes the nearest
 * possible thing to everywhere — the optimiser would route through it first and
 * the day would come back confidently wrong.
 */
class OsrmRouteClientTest {

    private final OsrmRouteClient client = new OsrmRouteClient(properties(),
            JsonMapper.builder().build(), new RateGate(0, 1000));

    @Test
    void aTableComesBackAsTwoSquareMatrices() {
        RouteClient.Matrix matrix = client.parse("""
                {"code":"Ok",
                 "durations":[[0,300.4,600],[290,0,410],[610,400,0]],
                 "distances":[[0,1200,2500],[1180,0,1600],[2510,1610,0]],
                 "sources":[],"destinations":[]}
                """, 3);

        assertThat(matrix.size()).isEqualTo(3);
        // Seconds arrive as JSON floats and are rounded, not truncated: a leg
        // is a whole number of seconds everywhere else in this feature.
        assertThat(matrix.seconds()[0][1]).isEqualTo(300);
        assertThat(matrix.seconds()[2][0]).isEqualTo(610);
        assertThat(matrix.metres()[0][2]).isEqualTo(2500);
        // Asymmetric on purpose. Driving is, and the optimiser is written for it.
        assertThat(matrix.seconds()[1][0]).isNotEqualTo(matrix.seconds()[0][1]);
    }

    @Test
    void aStopTheEngineCannotReachIsRefusedRatherThanReadAsZero() {
        assertThatThrownBy(() -> client.parse("""
                {"code":"Ok",
                 "durations":[[0,null],[null,0]],
                 "distances":[[0,10],[10,0]]}
                """, 2))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("could not reach");
    }

    @Test
    void aRefusalCarriesTheEnginesOwnReason() {
        assertThatThrownBy(() -> client.parse("""
                {"code":"NoSegment","message":"Could not find a matching segment for coordinate 1"}
                """, 2))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("matching segment");
    }

    @Test
    void aTableOfTheWrongSizeIsNotQuietlyUsed() {
        // Two stops were asked about and one row came back. Trusting it would
        // pair the second place with the first place's travel times.
        assertThatThrownBy(() -> client.parse("""
                {"code":"Ok","durations":[[0]],"distances":[[0]]}
                """, 2))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("wrong size");
    }

    @Test
    void somethingThatIsNotJsonIsAnOutageRatherThanACrash() {
        assertThatThrownBy(() -> client.parse("<html>502 Bad Gateway</html>", 2))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    private static WanderProperties properties() {
        // Only the base URL is read in the constructor, and nothing here makes
        // a request.
        return new WanderProperties("test", "", "", false, "EUR", null, null, null, null, null,
                null, null, null, null,
                new WanderProperties.Routing(true, "http://localhost:1", 12, "", "", 0, 1000),
                null, null, null);
    }
}
