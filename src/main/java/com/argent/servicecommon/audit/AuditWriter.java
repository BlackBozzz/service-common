package com.argent.servicecommon.audit;

import com.argent.servicecommon.security.CurrentUser;
import com.argent.servicecommon.live.ArgentLiveTransaction;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Запись строки журнала аудита прямым SQL через {@link JdbcTemplate}; автор — {@link CurrentUser}, вне запроса пусто.
 *
 * <p>Соединение берётся из текущей Spring-транзакции, поэтому строка фиксируется и откатывается вместе с данными.
 * Вне Spring-транзакции {@link JdbcTemplate} берёт отдельное соединение в autocommit: строка фиксируется сразу и не
 * откатывается. Hibernate-сессия не используется: слушатели вызываются посреди flush, и сохранение сущности через
 * ту же сессию нарушило бы очередь её действий.</p>
 */
public class AuditWriter {

    private static final String INSERT_SQL = "INSERT INTO common_audit_entry "
            + "(entity_type, entity_id, action, field, old_value, new_value, actor_id, actor_login, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private volatile Runnable onAuditChanged = () -> { };

    public void setOnAuditChanged(Runnable callback) {
        this.onAuditChanged = Objects.requireNonNull(callback);
    }


    public AuditWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    /**
     * Добавляет строку; {@code entityId}/{@code field} — {@code null} для операции над таблицей или записью целиком.
     */
    public void write(String entityType, String entityId, String action, String field,
                      String oldValue, String newValue) {
        jdbcTemplate.update(INSERT_SQL, entityType, entityId, action, field, oldValue, newValue,
                CurrentUser.id().orElse(null), CurrentUser.login().orElse(null),
                OffsetDateTime.now(ZoneOffset.UTC));
        ArgentLiveTransaction.afterCommit(onAuditChanged);
    }
}
