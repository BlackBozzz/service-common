package com.argent.servicecommon.live;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgentLiveTransactionTest {
    @Test
    void defersNotificationsUntilCommit() {
        AtomicInteger count = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            ArgentLiveTransaction.afterCommit(count::incrementAndGet);
            assertEquals(0, count.get());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertEquals(1, count.get());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void notifiesImmediatelyWithoutTransaction() {
        AtomicInteger count = new AtomicInteger();
        ArgentLiveTransaction.afterCommit(count::incrementAndGet);
        assertEquals(1, count.get());
    }
}
