package com.argent.servicecommon.queue.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO для страницы очередей и REST API. */
public final class ArgentQueueDto {
    private ArgentQueueDto() { }

    public record Queue(UUID id, String name, boolean allowSkip, boolean paused,
                        long pending, long running, long retryWait, long succeeded, long cancelled) { }

    public record Task(UUID id, UUID queueId, String handlerKey, String status, int priority,
                       int attemptCount, Instant nextRunAt, Instant createdAt, Instant startedAt,
                       Instant finishedAt, String lastError, String idempotencyKey, long sequenceNo) {
        /** Для исходного API сохранён старый конструктор. */
        public Task(UUID id, UUID queueId, String handlerKey, String status, int priority,
                    int attemptCount, Instant nextRunAt, Instant createdAt, Instant startedAt,
                    Instant finishedAt, String lastError, String idempotencyKey) {
            this(id, queueId, handlerKey, status, priority, attemptCount, nextRunAt, createdAt,
                    startedAt, finishedAt, lastError, idempotencyKey, 0);
        }
    }

    public record Attempt(long id, int attemptNo, Instant startedAt, Instant finishedAt,
                          String result, String errorType, String errorMessage, String stackTrace) { }

    public record TaskDetails(Task task, String payloadJson, List<Attempt> attempts) { }

    public record Page<T>(List<T> items, long totalElements, int page, int size) { }

    public record QueueSettings(boolean allowSkip) { }
    public record PriorityChange(int priority) { }
}
