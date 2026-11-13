package com.argent.servicecommon.queue.dto;

/** Фильтры и сортировка списка задач (не влияют на порядок работы самого worker). */
public record ArgentQueueTaskFilter(
        String view, String search, String status, String handlerKey,
        Integer priority, Integer attemptCount, Long sequenceNo, String lastError,
        String sortField, String sortDirection
) {
    public static ArgentQueueTaskFilter all() {
        return new ArgentQueueTaskFilter("all", null, null, null, null, null, null, null, null, null);
    }
}
