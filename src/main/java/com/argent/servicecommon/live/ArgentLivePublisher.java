package com.argent.servicecommon.live;

/** Signals that administrative data has changed. No business data is sent over WebSocket. */
public interface ArgentLivePublisher {
    void queueChanged();
    void auditChanged();
}
