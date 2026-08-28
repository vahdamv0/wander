package com.wander.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.wander.PostgresIntegrationTest;

/**
 * A change is announced when it is true, not when it is attempted.
 *
 * Worth its own test because the failure mode is invisible: with a plain
 * `@EventListener`, every one of these would still pass its own HTTP assertions
 * while telling every other viewer to re-read a write that never landed. The
 * listener's `AFTER_COMMIT` phase is the whole difference, and nothing else in
 * the suite would notice if it were dropped.
 */
@PostgresIntegrationTest
class TripChangeCommitTest {

    /** Spied rather than mocked: the real fan-out still runs, we just count calls. */
    @MockitoSpyBean
    private TripSyncHandler handler;

    @Autowired
    private TripChanges changes;

    private TransactionTemplate tx;

    @Autowired
    void transactions(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void aChangeInARolledBackTransactionIsNeverBroadcast() {
        tx.executeWithoutResult(status -> {
            changes.itineraryChanged(1L, 7L);
            // What a failed write looks like from the listener's point of view.
            status.setRollbackOnly();
        });

        verify(handler, never()).broadcast(any());
    }

    @Test
    void aChangeThatCommitsIsBroadcastOnce() {
        tx.executeWithoutResult(status -> changes.itineraryChanged(2L, 7L));

        // Synchronous, on the committing thread — so by the time the transaction
        // template returns, the frames are already out.
        verify(handler).broadcast(TripChange.itinerary(2L, 7L));
    }

    @Test
    void aChangePublishedWithNoTransactionStillArrives() {
        // fallbackExecution on the listener. Without it these vanish silently,
        // which is a miserable thing to debug.
        changes.membersChanged(3L, 7L);

        verify(handler).broadcast(TripChange.members(3L, 7L));
    }
}
