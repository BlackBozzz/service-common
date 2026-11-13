package com.argent.servicecommon.queue.service;

import com.argent.servicecommon.queue.dto.ArgentQueueTaskFilter;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgentQueueTaskOrderingTest {
    @Test
    void defaultOrderKeepsActiveTasksFirstAndRespectsQueueMode() {
        String sql = ArgentQueueStore.taskOrderBy(ArgentQueueTaskFilter.all());
        assertTrue(sql.contains("t.next_run_at <= now()"));
        assertTrue(sql.contains("q.allow_skip THEN t.priority END DESC"));
        assertTrue(sql.contains("q.allow_skip THEN t.next_run_at END ASC"));
        assertTrue(sql.contains("THEN t.sequence_no END ASC"));
        assertTrue(sql.contains("THEN t.sequence_no END DESC"));
        assertFalse(sql.contains("ORDER BY t.id"));
    }

    @Test
    void explicitSortUsesWhitelistedColumnButStillGroupsActiveFirst() {
        ArgentQueueTaskFilter query = new ArgentQueueTaskFilter(
                "all", null, null, null, null, null, null, null, "priority", "desc");
        String sql = ArgentQueueStore.taskOrderBy(query);
        assertTrue(sql.contains("THEN 0 ELSE 1 END ASC, t.priority DESC NULLS LAST"));
    }

    @Test
    void userCannotInjectSqlThroughSortFieldOrDirection() {
        assertThrows(ResponseStatusException.class, () -> ArgentQueueStore.taskOrderBy(
                new ArgentQueueTaskFilter("all", null, null, null, null, null, null, null,
                        "priority; DROP TABLE argent_queue_task", "desc")));
        assertThrows(ResponseStatusException.class, () -> ArgentQueueStore.taskOrderBy(
                new ArgentQueueTaskFilter("all", null, null, null, null, null, null, null,
                        "priority", "desc; DROP")));
    }
}
