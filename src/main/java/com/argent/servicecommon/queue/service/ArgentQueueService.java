package com.argent.servicecommon.queue.service;

import com.argent.servicecommon.queue.dto.ArgentQueueDto;
import com.argent.servicecommon.queue.dto.ArgentQueueTaskFilter;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Публичный API постановки задач: очередь, зарегистрированный Java-обработчик, JSON-совместимый DTO. */
public class ArgentQueueService {
    private final ArgentQueueStore store;
    private final ArgentQueueRegistry registry;
    private final JsonMapper mapper;

    public ArgentQueueService(ArgentQueueStore store, ArgentQueueRegistry registry, JsonMapper mapper) {
        this.store = Objects.requireNonNull(store);
        this.registry = Objects.requireNonNull(registry);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public UUID createQueueTask(String queueName, String handlerKey, Object payload) {
        return createQueueTask(queueName, handlerKey, payload, 0, null);
    }

    public UUID createQueueTask(String queueName, String handlerKey, Object payload, int priority) {
        return createQueueTask(queueName, handlerKey, payload, priority, null);
    }

    /** idempotencyKey предотвращает повторную постановку одной и той же задачи в очередь. */
    public UUID createQueueTask(String queueName, String handlerKey, Object payload,
                                int priority, String idempotencyKey) {
        String name = Objects.requireNonNull(queueName, "Название очереди обязательно").trim();
        String handler = Objects.requireNonNull(handlerKey, "Ключ обработчика обязателен").trim();
        if (name.isEmpty() || name.length() > 160) throw new IllegalArgumentException("Название очереди: 1–160 символов");
        if (handler.isEmpty() || handler.length() > 160) throw new IllegalArgumentException("Ключ обработчика: 1–160 символов");
        if (!registry.contains(handler)) throw new IllegalArgumentException("Обработчик не зарегистрирован: " + handler);
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 200)) {
            throw new IllegalArgumentException("idempotencyKey должен содержать от 1 до 200 символов");
        }
        String json = mapper.writeValueAsString(Objects.requireNonNull(payload, "DTO задачи обязателен"));
        if (json.getBytes(StandardCharsets.UTF_8).length > 256_000) {
            throw new IllegalArgumentException("Параметры задачи превышают 256 КБ");
        }
        return store.enqueue(name, handler, json, priority, idempotencyKey);
    }

    /** Конфигурация по имени; удобно вызвать один раз при инициализации бизнес-модуля. */
    public UUID configureQueue(String queueName, boolean allowSkip) {
        String name = Objects.requireNonNull(queueName).trim();
        if (name.isEmpty() || name.length() > 160) {
            throw new IllegalArgumentException("Название очереди: 1–160 символов");
        }
        return store.configureQueue(name, allowSkip);
    }

    /** В строгой очереди приоритет не может обойти более старую незавершённую задачу. */
    public void setQueueAllowSkip(UUID queueId, boolean allowSkip) {
        store.allowSkip(queueId, allowSkip);
    }

    public List<ArgentQueueDto.Queue> listQueues() { return store.queues(); }
    public ArgentQueueDto.Page<ArgentQueueDto.Task> listTasks(UUID queueId, int page, int size) {
        return store.tasks(queueId, page, size);
    }
    public ArgentQueueDto.Page<ArgentQueueDto.Task> listTasks(
            UUID queueId, int page, int size, ArgentQueueTaskFilter filter) {
        return store.tasks(queueId, page, size, filter);
    }
    public ArgentQueueDto.TaskDetails task(UUID id) { return store.details(id); }
    public void pauseQueue(UUID id) { store.pause(id, true); }
    public void resumeQueue(UUID id) { store.pause(id, false); }
    public int runQueueNow(UUID id) { return store.runQueueNow(id); }
    public void retryTask(UUID id) { store.retryTask(id); }
    public void setPriority(UUID id, int priority) { store.priority(id, priority); }
    public void cancelTask(UUID id) { store.cancel(id); }
}
