package com.wander.route;

/**
 * The order to visit a day's stops in, given how long it takes to get between
 * each pair of them.
 *
 * Pure and static, like {@code ExpenseSplitter} and for the same reasons: it is
 * the part with the arithmetic in it, so it is the part that deserves a unit
 * test rather than an HTTP one, and it must give the same answer twice.
 *
 * <h2>Nearest neighbour, then 2-opt</h2>
 *
 * Nearest neighbour builds a route by always going to the closest stop not yet
 * visited. It is fast, it is what a person does by eye, and it reliably ends
 * with one long limp back across the city — so 2-opt then repeatedly takes a
 * stretch of the route and reverses it, keeping any reversal that shortens the
 * whole thing. That is enough for a day: with a dozen stops it lands on the
 * optimum or within a few per cent of it, and the alternative (an exact solver)
 * is a different kind of program for a problem nobody has.
 *
 * <h2>Pinned stops</h2>
 *
 * A pinned stop keeps its **index**. The rest are permuted into the slots that
 * are left. Two quite different things pin a stop and both matter:
 *
 *  - the user locked it — the hotel to start from, the table booked for eight;
 *  - it has no coordinates, so the routing engine was never told about it.
 *
 * The second is not a technicality. Moving a stop the engine could not see
 * would be rearranging somebody's plan around a guess, and dropping it to the
 * end would be worse: it is usually the note-to-self ("pick up tickets") that
 * was typed rather than searched.
 *
 * A 2-opt move reverses a stretch of the route, so a stretch containing a
 * pinned slot is simply never considered. That single rule is the whole
 * mechanism — there is no second code path for locked stops to disagree with.
 *
 * <h2>Where the route starts</h2>
 *
 * At whatever is in the first slot today. There is no depot: a day does not
 * start from anywhere in particular unless somebody said so, and if they did
 * say so they locked it. Seeding from the current first stop also makes the
 * result predictable — sorting twice does not shuffle the morning.
 */
public final class DayRouteOptimiser {

    /**
     * A cap on the improvement loop. 2-opt converges long before this on a
     * dozen stops; the cap is here so a pathological matrix (all-equal costs,
     * say) cannot spin.
     */
    private static final int MAX_PASSES = 64;

    private DayRouteOptimiser() {
    }

    /**
     * The new order, as original indices: {@code result[slot]} is the stop that
     * should end up at that slot.
     *
     * @param seconds  square matrix, {@code seconds[i][j]} from stop i to stop j
     * @param pinned   whether the stop at each index must stay at that index
     * @param placeIds the tie-break, so two equally close stops resolve the same
     *                 way on every run — lowest id wins, as {@code ExpenseSplitter}
     *                 spreads its remainder to the lowest user id
     */
    public static int[] order(long[][] seconds, boolean[] pinned, long[] placeIds) {
        int n = pinned.length;
        if (seconds.length != n || placeIds.length != n) {
            throw new IllegalArgumentException("The matrix and the stops disagree about the day");
        }
        if (n < 2) {
            return identity(n);
        }
        int[] route = nearestNeighbour(seconds, pinned, placeIds);
        improve(route, seconds, pinned);
        return route;
    }

    /**
     * What a route costs, summed leg by leg — the time when handed the duration
     * matrix, and the distance when handed the distance one, which is the only
     * reason this is not called {@code totalSeconds}. "Better" always means the
     * time.
     */
    public static long total(int[] route, long[][] matrix) {
        long cost = 0;
        for (int i = 0; i + 1 < route.length; i++) {
            cost += matrix[route[i]][route[i + 1]];
        }
        return cost;
    }

    private static int[] nearestNeighbour(long[][] seconds, boolean[] pinned, long[] placeIds) {
        int n = pinned.length;
        int[] route = new int[n];
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (pinned[i]) {
                route[i] = i;
                used[i] = true;
            }
        }

        int previous = -1;
        for (int slot = 0; slot < n; slot++) {
            if (pinned[slot]) {
                previous = slot;
                continue;
            }
            // The first free slot with nothing before it keeps its own stop: the
            // day starts where it started. See the class note.
            int chosen = previous < 0 ? firstUnused(used, slot) : nearest(previous, used, seconds, placeIds);
            route[slot] = chosen;
            used[chosen] = true;
            previous = chosen;
        }
        return route;
    }

    private static int firstUnused(boolean[] used, int preferred) {
        if (!used[preferred]) {
            return preferred;
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        throw new IllegalStateException("Every stop is already placed");
    }

    private static int nearest(int from, boolean[] used, long[][] seconds, long[] placeIds) {
        int best = -1;
        for (int candidate = 0; candidate < used.length; candidate++) {
            if (used[candidate]) {
                continue;
            }
            if (best < 0 || seconds[from][candidate] < seconds[from][best]
                    // Equal legs are common on a coarse graph, and a coin toss
                    // here would make the answer differ between two runs on the
                    // same day.
                    || (seconds[from][candidate] == seconds[from][best]
                            && placeIds[candidate] < placeIds[best])) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 2-opt: reverse any stretch that shortens the whole route, until none
     * does. A stretch covering a pinned slot is skipped, which is what keeps a
     * locked stop at its index.
     */
    private static void improve(int[] route, long[][] seconds, boolean[] pinned) {
        int n = route.length;
        long current = total(route, seconds);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean improved = false;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (spansAPin(pinned, i, j)) {
                        continue;
                    }
                    reverse(route, i, j);
                    long candidate = total(route, seconds);
                    if (candidate < current) {
                        current = candidate;
                        improved = true;
                    } else {
                        reverse(route, i, j);
                    }
                }
            }
            if (!improved) {
                return;
            }
        }
    }

    private static boolean spansAPin(boolean[] pinned, int from, int to) {
        for (int slot = from; slot <= to; slot++) {
            if (pinned[slot]) {
                return true;
            }
        }
        return false;
    }

    private static void reverse(int[] route, int from, int to) {
        while (from < to) {
            int held = route[from];
            route[from] = route[to];
            route[to] = held;
            from++;
            to--;
        }
    }

    private static int[] identity(int n) {
        int[] route = new int[n];
        for (int i = 0; i < n; i++) {
            route[i] = i;
        }
        return route;
    }
}
