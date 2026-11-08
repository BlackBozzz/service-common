package com.argent.servicecommon.audit;

import com.argent.servicecommon.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Запись строки журнала аудита прямым SQL через {@link JdbcTemplate}.
 *
 * <p>JDBC-соединение берётся из текущей Spring-транзакции, поэтому запись аудита фиксируется и
 * откатывается вместе с изменением данных. Hibernate-сессия не используется: слушатели вызываются
 * посреди flush, и сохранение сущности через ту же сессию нарушило бы очередь её действий.</p>
 *
 * <p>Автор изменения — текущий пользователь ({@link CurrentUser#id()}, {@link CurrentUser#login()});
 * для анонимного или системного вызова оба поля пустые.</p>
 */
public class AuditWriter {

    private static final String INSERT_SQL = "INSERT INTO common_audit_entry "
            + "(entity_type, entity_id, action, field, old_value, new_value, actor_id, actor_login, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public AuditWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    /**
     * Добавляет строку журнала.
     *
     * @param entityType имя таблицы сущности
     * @param entityId   идентификатор записи; {@code null} для операции над всей таблицей
     * @param action     INSERT, UPDATE, DELETE или REPLACE
     * @param field      изменённое свойство; {@code null} для записи целиком
     */
    public void write(String entityType, String entityId, String action, String field,
                      String oldValue, String newValue) {
        jdbcTemplate.update(INSERT_SQL, entityType, entityId, action, field, oldValue, newValue,
                CurrentUser.id().orElse(null), CurrentUser.login().orElse(null),
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
