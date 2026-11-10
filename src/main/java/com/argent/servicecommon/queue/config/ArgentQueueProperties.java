package com.argent.servicecommon.queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки фонового исполнителя; параметры доступны как argent.queue.* в application.properties. */
@ConfigurationProperties(prefix = "argent.queue")
public class ArgentQueueProperties {
    private boolean enabled = true;
    private int maxConcurrentQueues = 4;
    private long pollIntervalMs = 2000;
    private long heartbeatIntervalMs = 15000;
    private int leaseSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxConcurrentQueues() { return Math.max(1, maxConcurrentQueues); }
    public void setMaxConcurrentQueues(int value) { maxConcurrentQueues = value; }
    public long getPollIntervalMs() { return Math.max(500, pollIntervalMs); }
    public void setPollIntervalMs(long value) { pollIntervalMs = value; }
    public long getHeartbeatIntervalMs() { return Math.max(1000, heartbeatIntervalMs); }
    public void setHeartbeatIntervalMs(long value) { heartbeatIntervalMs = value; }
    public int getLeaseSeconds() { return Math.max(30, leaseSeconds); }
    public void setLeaseSeconds(int value) { leaseSeconds = value; }
}
