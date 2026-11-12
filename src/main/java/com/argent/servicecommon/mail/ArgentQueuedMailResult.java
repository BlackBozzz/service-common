package com.argent.servicecommon.mail;

import java.util.List;
import java.util.UUID;

/** Постановка в очередь — ещё НЕ доставка на почтовый сервер. */
public record ArgentQueuedMailResult(int selected, int queued, int skipped, List<UUID> taskIds) {
    public ArgentQueuedMailResult {
        taskIds = List.copyOf(taskIds);
    }
}
