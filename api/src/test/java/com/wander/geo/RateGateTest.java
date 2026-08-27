package com.wander.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.wander.common.RateLimitedException;

/**
 * A plain unit test — no Spring, no Postgres. The interval is milliseconds here
 * rather than the configured second, so the whole class runs in well under a
 * second while still asserting the two behaviours that matter: callers are
 * spaced out, and a caller that would wait too long is turned away instead of
 * queueing.
 */
class RateGateTest {

    @Test
    void callersAreSpacedByAtLeastTheInterval() {
        RateGate gate = new RateGate(50, 1_000);

        long start = System.nanoTime();
        gate.pass();
        gate.pass();
        gate.pass();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // The first call goes straight through, so three calls cost two gaps.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(100);
    }

    @Test
    void aCallerThatWouldWaitTooLongIsRejected() {
        // Nobody may wait: the second caller has nowhere to queue.
        RateGate gate = new RateGate(5_000, 0);

        gate.pass();
        assertThatThrownBy(gate::pass)
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("Too many place searches");
    }

    @Test
    void concurrentCallersEachGetTheirOwnSlot() throws Exception {
        RateGate gate = new RateGate(30, 5_000);
        List<Callable<Long>> callers = List.of(stamp(gate), stamp(gate), stamp(gate), stamp(gate));

        List<Long> passedAt;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            passedAt = pool.invokeAll(callers).stream().map(RateGateTest::value).sorted().toList();
        }

        // Each slot is reserved before its sleep, so four threads arriving at
        // once leave spaced out rather than all waking to the same moment.
        for (int i = 1; i < passedAt.size(); i++) {
            long gapMillis = (passedAt.get(i) - passedAt.get(i - 1)) / 1_000_000;
            assertThat(gapMillis).as("gap between caller %d and %d", i - 1, i)
                    .isGreaterThanOrEqualTo(25);
        }
    }

    private static Callable<Long> stamp(RateGate gate) {
        return () -> {
            gate.pass();
            return System.nanoTime();
        };
    }

    private static Long value(Future<Long> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
