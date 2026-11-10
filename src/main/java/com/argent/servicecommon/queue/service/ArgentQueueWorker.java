package com.argent.servicecommon.queue.service;

import com.argent.servicecommon.queue.config.ArgentQueueProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Фоновый исполнитель с лимитом одновременных очередей, lease и восстановлением при авариях. */
public class ArgentQueueWorker {
    private static final Logger log = LoggerFactory.getLogger(ArgentQueueWorker.class);
    private final ArgentQueueStore store;
    private final ArgentQueueRegistry registry;
    private final ArgentQueueProperties properties;
    private final Semaphore capacity;
    private final ExecutorService executor;
    private final String workerId = UUID.randomUUID().toString();
    private final Map<UUID, ArgentQueueStore.Claim> active = new ConcurrentHashMap<>();
    private volatile boolean closing;

    public ArgentQueueWorker(ArgentQueueStore store, ArgentQueueRegistry registry, ArgentQueueProperties properties) {
        this.store = store;
        this.registry = registry;
        this.properties = properties;
        this.capacity = new Semaphore(properties.getMaxConcurrentQueues());
        this.executor = Executors.newFixedThreadPool(properties.getMaxConcurrentQueues(),
                Thread.ofVirtual().name("argent-queue-", 0).factory());
    }

    @Scheduled(fixedDelayString = "${argent.queue.poll-interval-ms:2000}")
    public void poll() {
        if (closing) return;
        try {
            store.recoverExpired();
            while (!closing && capacity.tryAcquire()) {
                ArgentQueueStore.Claim claim;
                try {
                    claim = store.claim(workerId, properties.getLeaseSeconds());
                } catch (Exception exception) {
                    capacity.release();
                    throw exception;
                }
                if (claim == null) {
                    capacity.release();
                    break;
                }
                active.put(claim.taskId(), claim);
                try {
                    executor.execute(() -> execute(claim));
                } catch (RuntimeException exception) {
                    active.remove(claim.taskId());
                    capacity.release();
                    throw exception;
                }
            }
        } catch (Exception exception) {
            log.error("Не удалось получить задачи Argent Queue", exception);
        }
    }

    @Scheduled(fixedDelayString = "${argent.queue.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        for (ArgentQueueStore.Claim claim : active.values()) {
            try {
                if (!store.heartbeat(claim, properties.getLeaseSeconds())) {
                    log.warn("Потеряно право выполнения задачи {}", claim.taskId());
                }
            } catch (Exception exception) {
                log.warn("Не удалось продлить lease задачи {}", claim.taskId(), exception);
            }
        }
    }

    private void execute(ArgentQueueStore.Claim claim) {
        try {
            registry.execute(claim.handlerKey(), claim.payloadJson());
            store.success(claim);
        } catch (Throwable exception) {
            Throwable error = exception;
            String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            StringWriter buffer = new StringWriter();
            error.printStackTrace(new PrintWriter(buffer));
            try {
                store.failure(claim, limit(error.getClass().getName(), 300),
                        limit(text, 2000), limit(buffer.toString(), 12000));
            } catch (Exception persistenceError) {
                log.error("Не удалось записать ошибку задачи {}", claim.taskId(), persistenceError);
            }
            log.warn("Ошибка Argent Queue: task={}, попытка={}, причина={}", claim.taskId(), claim.attemptNo(), text);
        } finally {
            active.remove(claim.taskId());
            capacity.release();
        }
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    public void shutdown() {
        closing = true;
        executor.shutdown();
    }
}
