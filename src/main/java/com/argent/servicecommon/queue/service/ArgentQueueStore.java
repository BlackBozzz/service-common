package com.argent.servicecommon.queue.service;

import com.argent.servicecommon.queue.dto.ArgentQueueDto;
import com.argent.servicecommon.queue.dto.ArgentQueueTaskFilter;
import com.argent.servicecommon.live.ArgentLiveTransaction;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

/** Работа с PostgreSQL; захват очереди и запись результата выполняются короткими транзакциями. */
public class ArgentQueueStore {
    private static final String TASK_FIELDS = "id, queue_id, handler_key, status, priority, attempt_count, "
            + "next_run_at, created_at, started_at, finished_at, last_error, idempotency_key, sequence_no";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private volatile Runnable onQueueChanged = () -> { };

    /** Optional callback; lets other projects use the queue without a WebSocket server. */
    public void setOnQueueChanged(Runnable callback) {
        this.onQueueChanged = Objects.requireNonNull(callback);
    }

    private void changed() {
        ArgentLiveTransaction.afterCommit(onQueueChanged);
    }

    public ArgentQueueStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    /** Добавление соединяется с внешней бизнес-транзакцией, если она уже существует. */
    public UUID enqueue(String queueName, String handlerKey, String payloadJson, int priority, String idempotencyKey) {
        return transaction.execute(tx -> {
            UUID queueId = ensureQueue(queueName);
            UUID id = UUID.randomUUID();
            int updated = jdbc.update("""
                    INSERT INTO argent_queue_task(id, queue_id, handler_key, payload_json, priority, idempotency_key)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (queue_id, idempotency_key) DO NOTHING
                    """, id, queueId, handlerKey, payloadJson, priority, idempotencyKey);
            if (updated == 0 && idempotencyKey != null) {
                return jdbc.queryForObject("SELECT id FROM argent_queue_task WHERE queue_id = ? AND idempotency_key = ?",
                        UUID.class, queueId, idempotencyKey);
            }
            changed();
            return id;
        });
    }

    private UUID ensureQueue(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO argent_queue(id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", id, name);
        return jdbc.queryForObject("SELECT id FROM argent_queue WHERE name = ?", UUID.class, name);
    }

    /** Явно задаёт режим очереди в бизнес-коде, без зависимости от административного интерфейса. */
    public UUID configureQueue(String name, boolean allowSkip) {
        return transaction.execute(tx -> {
            UUID id = ensureQueue(name);
            jdbc.update("UPDATE argent_queue SET allow_skip = ?, updated_at = now() WHERE id = ?", allowSkip, id);
            changed();
            return id;
        });
    }

    public List<ArgentQueueDto.Queue> queues() {
        return jdbc.query("""
                SELECT q.id, q.name, q.allow_skip, q.paused,
                       count(t.id) FILTER (WHERE t.status = 'PENDING') AS pending,
                       count(t.id) FILTER (WHERE t.status = 'RUNNING') AS running,
                       count(t.id) FILTER (WHERE t.status = 'RETRY_WAIT') AS retry_wait,
                       count(t.id) FILTER (WHERE t.status = 'SUCCESS') AS succeeded,
                       count(t.id) FILTER (WHERE t.status = 'CANCELLED') AS cancelled
                FROM argent_queue q LEFT JOIN argent_queue_task t ON t.queue_id = q.id
                GROUP BY q.id, q.name, q.allow_skip, q.paused
                ORDER BY q.name
                """, (rs, row) -> new ArgentQueueDto.Queue(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("allow_skip"),
                rs.getBoolean("paused"), rs.getLong("pending"), rs.getLong("running"),
                rs.getLong("retry_wait"), rs.getLong("succeeded"), rs.getLong("cancelled")));
    }

    /** Совместимый метод: весь список, безопасная сортировка исполнения по умолчанию. */
    public ArgentQueueDto.Page<ArgentQueueDto.Task> tasks(UUID queueId, int page, int size) {
        return tasks(queueId, page, size, ArgentQueueTaskFilter.all());
    }

    /**
     * Фильтрация и сортировка выполняются SQL-запросом ДО LIMIT/OFFSET:
     * AG Grid получает корректный totalElements и настоящую страницу данных.
     */
    public ArgentQueueDto.Page<ArgentQueueDto.Task> tasks(
            UUID queueId, int page, int size, ArgentQueueTaskFilter filter) {
        if (page < 0 || size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректные параметры страницы");
        }
        requireQueue(queueId);
        ArgentQueueTaskFilter actual = filter == null ? ArgentQueueTaskFilter.all() : filter;
        List<Object> arguments = new ArrayList<>();
        arguments.add(queueId);
        StringBuilder where = new StringBuilder(" WHERE t.queue_id = ?");

        String view = actual.view() == null ? "all" : actual.view().trim().toLowerCase(Locale.ROOT);
        switch (view) {
            case "", "all" -> { }
            case "done" -> where.append(" AND t.status = 'SUCCESS'");
            case "waiting" -> where.append(" AND t.status IN ('PENDING', 'RETRY_WAIT', 'PAUSED')");
            case "errors" -> where.append(" AND t.status = 'RETRY_WAIT'");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный фильтр задач");
        }

        String status = blankToNull(actual.status());
        if (status != null) {
            if (!TASK_STATUSES.contains(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный статус задачи");
            }
            where.append(" AND t.status = ?");
            arguments.add(status);
        }
        appendContains(where, arguments, "t.handler_key", actual.handlerKey());
        appendContains(where, arguments, "t.last_error", actual.lastError());
        if (actual.priority() != null) {
            where.append(" AND t.priority = ?");
            arguments.add(actual.priority());
        }
        if (actual.sequenceNo() != null) {
            if (actual.sequenceNo() < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Номер задачи должен быть положительным");
            }
            where.append(" AND t.sequence_no = ?");
            arguments.add(actual.sequenceNo());
        }
        if (actual.attemptCount() != null) {
            if (actual.attemptCount() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Количество попыток не может быть отрицательным");
            }
            where.append(" AND t.attempt_count = ?");
            arguments.add(actual.attemptCount());
        }
        String search = blankToNull(actual.search());
        if (search != null) {
            where.append(" AND (lower(t.handler_key) LIKE ? ESCAPE '!' OR ")
                    .append("lower(coalesce(t.last_error, '')) LIKE ? ESCAPE '!' OR ")
                    .append("lower(cast(t.id as text)) LIKE ? ESCAPE '!')");
            String value = likeValue(search);
            arguments.add(value);
            arguments.add(value);
            arguments.add(value);
        }

        String from = " FROM argent_queue_task t JOIN argent_queue q ON q.id = t.queue_id";
        Long count = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        String selectedFields = "t." + TASK_FIELDS.replace(", ", ", t.");
        String sql = "SELECT " + selectedFields + from + where + taskOrderBy(actual)
                + " LIMIT ? OFFSET ?";
        List<ArgentQueueDto.Task> data = jdbc.query(sql, taskMapper(), pageArguments.toArray());
        return new ArgentQueueDto.Page<>(data, count == null ? 0 : count, page, size);
    }

    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING", "RUNNING", "RETRY_WAIT", "SUCCESS", "PAUSED", "CANCELLED");
    private static final Map<String, String> TASK_SORT_COLUMNS = Map.of(
            "handlerKey", "t.handler_key", "status", "t.status", "priority", "t.priority",
            "attemptCount", "t.attempt_count", "createdAt", "t.created_at",
            "nextRunAt", "t.next_run_at", "finishedAt", "t.finished_at",
            "sequenceNo", "t.sequence_no");

    /**
     * UUID генерируется случайно и не задаёт порядок очереди. sequence_no — монотонный номер постановки.
     * При allow_skip=true исполнитель сортирует готовые задачи по приоритету и next_run_at;
     * при allow_skip=false приоритет не обгоняет FIFO. Выполненные — в конце, новые выше старых.
     */
    static String taskOrderBy(ArgentQueueTaskFilter filter) {
        String field = blankToNull(filter.sortField());
        String direction = blankToNull(filter.sortDirection());
        StringBuilder order = new StringBuilder(" ORDER BY CASE WHEN t.status IN ")
                .append("('PENDING','RUNNING','RETRY_WAIT','PAUSED') THEN 0 ELSE 1 END ASC, ");
        if (field != null) {
            String column = TASK_SORT_COLUMNS.get(field);
            if (column == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сортировка по полю запрещена: " + field);
            }
            if (direction == null || (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ожидается сортировка asc или desc");
            }
            order.append(column).append(" ").append(direction.toUpperCase(Locale.ROOT))
                    .append(" NULLS LAST, ");
        } else if (direction != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указано поле сортировки");
        } else {
            // При разрешённом пропуске готовые задачи выше отложенных повторов.
            // В строгом FIFO readiness не должна нарушать порядок sequence_no.
            order.append("CASE WHEN q.allow_skip AND t.status IN ")
                    .append("('PENDING','RUNNING','RETRY_WAIT','PAUSED') THEN CASE WHEN ")
                    .append("t.status = 'RUNNING' OR (t.status IN ('PENDING','RETRY_WAIT') ")
                    .append("AND t.next_run_at <= now()) THEN 0 ELSE 1 END END ASC NULLS LAST, ")
                    .append("CASE WHEN t.status IN ('PENDING','RUNNING','RETRY_WAIT','PAUSED') ")
                    .append("AND q.allow_skip THEN t.priority END DESC NULLS LAST, ")
                    .append("CASE WHEN t.status IN ('PENDING','RUNNING','RETRY_WAIT','PAUSED') ")
                    .append("AND q.allow_skip THEN t.next_run_at END ASC NULLS LAST, ");
        }
        return order.append("CASE WHEN t.status IN ('PENDING','RUNNING','RETRY_WAIT','PAUSED') ")
                .append("THEN t.sequence_no END ASC NULLS LAST, ")
                .append("CASE WHEN t.status IN ('SUCCESS','CANCELLED') ")
                .append("THEN t.sequence_no END DESC NULLS LAST, t.id ASC")
                .toString();
    }

    private static void appendContains(StringBuilder where, List<Object> arguments, String column, String value) {
        if (blankToNull(value) == null) return;
        where.append(" AND lower(").append(column).append(") LIKE ? ESCAPE '!'");
        arguments.add(likeValue(value));
    }

    private static String likeValue(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public ArgentQueueDto.TaskDetails details(UUID taskId) {
        List<ArgentQueueDto.Task> tasks = jdbc.query(
                "SELECT " + TASK_FIELDS + " FROM argent_queue_task WHERE id = ?", taskMapper(), taskId);
        if (tasks.isEmpty()) throw notFound("Задача не найдена");
        String json = jdbc.queryForObject("SELECT payload_json FROM argent_queue_task WHERE id = ?", String.class, taskId);
        return new ArgentQueueDto.TaskDetails(tasks.getFirst(), json, attempts(taskId));
    }

    public List<ArgentQueueDto.Attempt> attempts(UUID taskId) {
        return jdbc.query("""
                SELECT id, attempt_no, started_at, finished_at, result, error_type, error_message, stack_trace
                FROM argent_queue_task_attempt WHERE task_id = ? ORDER BY attempt_no DESC LIMIT 100
                """, (rs, row) -> new ArgentQueueDto.Attempt(
                rs.getLong("id"), rs.getInt("attempt_no"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("result"), rs.getString("error_type"),
                rs.getString("error_message"), rs.getString("stack_trace")), taskId);
    }

    public void pause(UUID queueId, boolean paused) {
        requireUpdated(jdbc.update("UPDATE argent_queue SET paused = ?, updated_at = now() WHERE id = ?", paused, queueId),
                "Очередь не найдена");
        changed();
    }

    public void allowSkip(UUID queueId, boolean allowSkip) {
        requireUpdated(jdbc.update("UPDATE argent_queue SET allow_skip = ?, updated_at = now() WHERE id = ?",
                allowSkip, queueId), "Очередь не найдена");
        changed();
    }

    /** Досрочно делает доступными все ожидающие повторы; текущие RUNNING не затрагивает. */
    public int runQueueNow(UUID queueId) {
        return transaction.execute(tx -> {
            List<Boolean> paused = jdbc.query("SELECT paused FROM argent_queue WHERE id = ? FOR UPDATE",
                    (rs, row) -> rs.getBoolean(1), queueId);
            if (paused.isEmpty()) throw notFound("Очередь не найдена");
            if (paused.getFirst()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Очередь на паузе. Сначала возобновите её.");
            int updated = jdbc.update("""
                    UPDATE argent_queue_task SET status = 'PENDING', next_run_at = now(), updated_at = now()
                    WHERE queue_id = ? AND status = 'RETRY_WAIT'
                    """, queueId);
            if (updated > 0) changed();
            return updated;
        });
    }

    public void retryTask(UUID taskId) {
        int count = jdbc.update("""
                UPDATE argent_queue_task SET status = 'PENDING', next_run_at = now(), updated_at = now()
                WHERE id = ? AND status = 'RETRY_WAIT'
                """, taskId);
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Повтор доступен только для задачи, ожидающей повторного запуска");
        changed();
    }

    public void priority(UUID taskId, int priority) {
        requireUpdated(jdbc.update("""
                UPDATE argent_queue_task SET priority = ?, updated_at = now()
                WHERE id = ? AND status IN ('PENDING', 'RETRY_WAIT', 'PAUSED')
                """, priority, taskId), "Нельзя изменить приоритет выполненной или работающей задачи");
        changed();
    }

    public void cancel(UUID taskId) {
        requireUpdated(jdbc.update("""
                UPDATE argent_queue_task SET status = 'CANCELLED', updated_at = now(), finished_at = now()
                WHERE id = ? AND status IN ('PENDING', 'RETRY_WAIT', 'PAUSED')
                """, taskId), "Нельзя отменить работающую или завершённую задачу");
        changed();
    }

    /** Подбирает задачу, атомарно удерживая строку очереди. Для строгого FIFO нельзя обойти ожидающую голову. */
    public Claim claim(String worker, int leaseSeconds) {
        return transaction.execute(tx -> {
            List<UUID> queues = jdbc.query("""
                    SELECT q.id FROM argent_queue q
                    WHERE NOT q.paused AND (q.lock_token IS NULL OR q.lease_until < now())
                      AND EXISTS (
                        SELECT 1 FROM argent_queue_task t
                        WHERE t.queue_id = q.id AND t.status IN ('PENDING', 'RETRY_WAIT')
                          AND t.next_run_at <= now()
                      )
                      AND (q.allow_skip OR EXISTS (
                        SELECT 1 FROM argent_queue_task head
                        WHERE head.queue_id = q.id AND head.status IN ('PENDING', 'RETRY_WAIT')
                          AND head.next_run_at <= now()
                          AND head.sequence_no = (
                            SELECT min(all_tasks.sequence_no) FROM argent_queue_task all_tasks
                            WHERE all_tasks.queue_id = q.id
                              AND all_tasks.status IN ('PENDING', 'RETRY_WAIT', 'RUNNING', 'PAUSED')
                          )
                      ))
                    ORDER BY q.updated_at, q.id
                    LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                    """, (rs, row) -> rs.getObject(1, UUID.class));
            if (queues.isEmpty()) return null;
            UUID queueId = queues.getFirst();
            boolean allowSkip = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT allow_skip FROM argent_queue WHERE id = ?", Boolean.class, queueId));
            String order = allowSkip ? "priority DESC, next_run_at ASC, sequence_no ASC" : "sequence_no ASC";
            String selectSql = """
                    SELECT id, handler_key, payload_json, attempt_count
                    FROM argent_queue_task WHERE queue_id = ?
                      AND status IN ('PENDING', 'RETRY_WAIT') AND next_run_at <= now()
                    """ + "ORDER BY " + order + " LIMIT 1";
            List<ClaimTask> tasks = jdbc.query(selectSql, (rs, row) ->
                    new ClaimTask(rs.getObject("id", UUID.class), rs.getString("handler_key"),
                            rs.getString("payload_json"), rs.getInt("attempt_count")), queueId);
            if (tasks.isEmpty()) return null;
            ClaimTask task = tasks.getFirst();
            UUID token = UUID.randomUUID();
            // Ручная отмена или повтор может изменить задачу между SELECT и UPDATE.
            // Условие в UPDATE не позволяет перехватить отменённую задачу.
            int updated = jdbc.update("""
                    UPDATE argent_queue_task SET status = 'RUNNING', attempt_count = attempt_count + 1,
                      started_at = now(), finished_at = NULL, updated_at = now()
                    WHERE id = ? AND status IN ('PENDING', 'RETRY_WAIT') AND next_run_at <= now()
                    """, task.id());
            if (updated != 1) return null;
            jdbc.update("""
                    UPDATE argent_queue SET lock_token = ?, locked_by = ?,
                      lease_until = now() + (? * interval '1 second'), updated_at = now()
                    WHERE id = ?
                    """, token, worker, leaseSeconds, queueId);
            jdbc.update("""
                    INSERT INTO argent_queue_task_attempt(task_id, attempt_no, started_at)
                    VALUES (?, ?, now())
                    """, task.id(), task.attemptCount() + 1);
            changed();
            return new Claim(queueId, task.id(), token, task.handlerKey(), task.payloadJson(),
                    task.attemptCount() + 1);
        });
    }

    /** Продлевает владение только пока токен не был перехвачен другим экземпляром. */
    public boolean heartbeat(Claim claim, int leaseSeconds) {
        return jdbc.update("""
                UPDATE argent_queue SET lease_until = now() + (? * interval '1 second')
                WHERE id = ? AND lock_token = ?
                """, leaseSeconds, claim.queueId(), claim.token()) == 1;
    }

    public void success(Claim claim) {
        transaction.executeWithoutResult(tx -> {
            if (!lockOwnedQueue(claim)) return;
            int updated = jdbc.update("""
                    UPDATE argent_queue_task SET status = 'SUCCESS', finished_at = now(),
                      last_error = NULL, updated_at = now()
                    WHERE id = ? AND status = 'RUNNING'
                      AND EXISTS (SELECT 1 FROM argent_queue WHERE id = ? AND lock_token = ?)
                    """, claim.taskId(), claim.queueId(), claim.token());
            if (updated == 1) {
                jdbc.update("""
                        UPDATE argent_queue_task_attempt SET result = 'SUCCESS', finished_at = now()
                        WHERE task_id = ? AND attempt_no = ? AND result = 'RUNNING'
                        """, claim.taskId(), claim.attemptNo());
                unlock(claim);
                changed();
            }
        });
    }

    public void failure(Claim claim, String errorType, String message, String trace) {
        transaction.executeWithoutResult(tx -> {
            if (!lockOwnedQueue(claim)) return;
            // Шесть первых повторов по расписанию; затем ровно раз в сутки.
            long[] delays = {60, 180, 300, 600, 1800, 3600};
            long delay = claim.attemptNo() <= delays.length ? delays[claim.attemptNo() - 1] : 86400;
            int updated = jdbc.update("""
                    UPDATE argent_queue_task SET status = 'RETRY_WAIT',
                      next_run_at = now() + (? * interval '1 second'), finished_at = now(),
                      last_error = ?, updated_at = now()
                    WHERE id = ? AND status = 'RUNNING'
                      AND EXISTS (SELECT 1 FROM argent_queue WHERE id = ? AND lock_token = ?)
                    """, delay, message, claim.taskId(), claim.queueId(), claim.token());
            if (updated == 1) {
                jdbc.update("""
                        UPDATE argent_queue_task_attempt SET result = 'FAILED', finished_at = now(),
                          error_type = ?, error_message = ?, stack_trace = ?
                        WHERE task_id = ? AND attempt_no = ? AND result = 'RUNNING'
                        """, errorType, message, trace, claim.taskId(), claim.attemptNo());
                unlock(claim);
                changed();
            }
        });
    }

    /** Единый порядок блокировок: сначала очередь, потом задача; защита от устаревшего исполнителя. */
    private boolean lockOwnedQueue(Claim claim) {
        return !jdbc.query("SELECT id FROM argent_queue WHERE id = ? AND lock_token = ? FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class), claim.queueId(), claim.token()).isEmpty();
    }

    private void unlock(Claim claim) {
        jdbc.update("""
                UPDATE argent_queue SET lock_token = NULL, locked_by = NULL, lease_until = NULL,
                  updated_at = now() WHERE id = ? AND lock_token = ?
                """, claim.queueId(), claim.token());
    }

    /** Реанимирует задачи после смерти JVM. Срок аренды снимается транзакционно. */
    public int recoverExpired() {
        return transaction.execute(tx -> {
            List<UUID> ids = jdbc.query("""
                    SELECT id FROM argent_queue
                    WHERE lock_token IS NOT NULL AND lease_until < now()
                    ORDER BY lease_until LIMIT 30 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> rs.getObject(1, UUID.class));
            for (UUID id : ids) {
                jdbc.update("""
                        UPDATE argent_queue_task_attempt SET result = 'FAILED', finished_at = now(),
                          error_type = 'WorkerLost', error_message = 'Истёк срок владения задачей',
                          stack_trace = NULL
                        WHERE result = 'RUNNING' AND task_id IN (
                          SELECT id FROM argent_queue_task WHERE queue_id = ? AND status = 'RUNNING')
                        """, id);
                jdbc.update("""
                        UPDATE argent_queue_task SET status = 'RETRY_WAIT', next_run_at = now(),
                          last_error = 'Исполнитель прерван; задача поставлена на повтор', updated_at = now()
                        WHERE queue_id = ? AND status = 'RUNNING'
                        """, id);
                jdbc.update("""
                        UPDATE argent_queue SET lock_token = NULL, locked_by = NULL, lease_until = NULL,
                          updated_at = now() WHERE id = ?
                        """, id);
            }
            if (!ids.isEmpty()) changed();
            return ids.size();
        });
    }

    private void requireQueue(UUID queueId) {
        Long exists = jdbc.queryForObject("SELECT count(*) FROM argent_queue WHERE id = ?", Long.class, queueId);
        if (exists == null || exists == 0) throw notFound("Очередь не найдена");
    }

    private void requireUpdated(int count, String message) {
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private RowMapper<ArgentQueueDto.Task> taskMapper() {
        return (rs, row) -> new ArgentQueueDto.Task(
                rs.getObject("id", UUID.class), rs.getObject("queue_id", UUID.class),
                rs.getString("handler_key"), rs.getString("status"), rs.getInt("priority"),
                rs.getInt("attempt_count"), instant(rs, "next_run_at"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("last_error"),
                rs.getString("idempotency_key"), rs.getLong("sequence_no"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ClaimTask(UUID id, String handlerKey, String payloadJson, int attemptCount) { }
    public record Claim(UUID queueId, UUID taskId, UUID token, String handlerKey,
                        String payloadJson, int attemptNo) { }
}
