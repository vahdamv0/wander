package com.wander.route;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The arithmetic, tested where the arithmetic is — a plain unit test, like
 * {@code ExpenseSplitterTest} and for the same reason: this is the part whose
 * wrong answer is plausible. A route that is merely a bit worse than it should
 * be looks exactly like a route that is right, so an HTTP test asserting "some
 * order came back" would pass on an optimiser that had stopped optimising.
 *
 * The matrices here are built from points on a line, where the best order is
 * something a reader can check by eye.
 */
class DayRouteOptimiserTest {

    /**
     * Four stops strung out along a road, planned in the order 0, 3, 1, 2 —
     * which walks the length of it three times. The best open route visits them
     * in order.
     */
    private static final long[] LINE = { 0, 30, 60, 90 };

    @Test
    void aZigZagIsStraightenedOut() {
        // Planned: 0km, 90km, 30km, 60km.
        long[] positions = { 0, 90, 30, 60 };
        long[][] cost = costsAlongALine(positions);

        int[] order = DayRouteOptimiser.order(cost, pins(4), ids(4));

        assertThat(order).containsExactly(0, 2, 3, 1);
        assertThat(DayRouteOptimiser.total(order, cost))
                .as("the length of the road, walked once")
                .isEqualTo(90);
    }

    @Test
    void anOrderThatIsAlreadyBestComesBackUnchanged() {
        long[][] cost = costsAlongALine(LINE);

        int[] order = DayRouteOptimiser.order(cost, pins(4), ids(4));

        assertThat(order).containsExactly(0, 1, 2, 3);
    }

    /**
     * The whole point of locking. The stop planned last is pinned there — the
     * table booked for eight — so the route may not simply reverse into it,
     * and the two free stops sort between the ends.
     */
    @Test
    void aLockedStopKeepsItsExactIndex() {
        long[] positions = { 0, 90, 30, 60 };
        long[][] cost = costsAlongALine(positions);
        boolean[] pinned = { false, true, false, false };

        int[] order = DayRouteOptimiser.order(cost, pinned, ids(4));

        assertThat(order[1]).as("the locked stop, still second").isEqualTo(1);
        assertThat(order).containsExactly(0, 1, 3, 2);
    }

    @Test
    void everyStopLockedIsADayNobodyCanImprove() {
        long[][] cost = costsAlongALine(new long[] { 0, 90, 30, 60 });
        boolean[] pinned = { true, true, true, true };

        assertThat(DayRouteOptimiser.order(cost, pinned, ids(4))).containsExactly(0, 1, 2, 3);
    }

    /**
     * Equal legs are ordinary on a coarse graph, and a coin toss would make the
     * same day sort differently on two runs. Lowest place id wins, the rule
     * {@code ExpenseSplitter} uses for its spare minor unit.
     */
    @Test
    void tiesBreakOnTheLowestPlaceId() {
        // Everything is one minute from everything else, so every order costs
        // the same and only the tie-break decides.
        long[][] cost = new long[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                cost[i][j] = i == j ? 0 : 60;
            }
        }
        long[] placeIds = { 500, 300, 400 };

        int[] order = DayRouteOptimiser.order(cost, pins(3), placeIds);

        assertThat(order).as("first stop stays, then id 300, then id 400")
                .containsExactly(0, 1, 2);
    }

    @Test
    void aDayWithOneStopIsLeftAlone() {
        assertThat(DayRouteOptimiser.order(new long[][] { { 0 } }, pins(1), ids(1)))
                .containsExactly(0);
    }

    /**
     * An asymmetric matrix is the normal case for driving — one-way systems —
     * and it is why the improvement loop scores a candidate by walking the
     * whole route rather than by the usual two-edge shortcut, which assumes
     * that reversing a stretch costs the same in both directions.
     */
    @Test
    void aOneWaySystemIsRespected() {
        // Going "up" the indices is cheap; coming back down is dear. The
        // planned order 0,2,1 has one expensive descent in it, and reversing
        // the tail removes it.
        long[][] cost = {
                { 0, 10, 20 },
                { 900, 0, 10 },
                { 900, 900, 0 },
        };

        int[] order = DayRouteOptimiser.order(cost, pins(3), ids(3));

        assertThat(order).containsExactly(0, 1, 2);
        assertThat(DayRouteOptimiser.total(order, cost)).isEqualTo(20);
    }

    /** {@code cost[i][j]} is the distance between two points on a line. */
    private static long[][] costsAlongALine(long[] positions) {
        int n = positions.length;
        long[][] cost = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                cost[i][j] = Math.abs(positions[i] - positions[j]);
            }
        }
        return cost;
    }

    private static boolean[] pins(int n) {
        return new boolean[n];
    }

    private static long[] ids(int n) {
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = 100L + i;
        }
        return ids;
    }
}
