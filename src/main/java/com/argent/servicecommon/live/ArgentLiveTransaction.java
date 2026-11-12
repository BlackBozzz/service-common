package com.argent.servicecommon.live;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publish invalidation only after a successful transaction commit. */
public final class ArgentLiveTransaction {
    private ArgentLiveTransaction() { }

    public static void afterCommit(Runnable notification) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notification.run();
                }
            });
        } else {
            notification.run();
        }
    }
}
