package com.argent.servicecommon.audit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Запись журнала аудита: одно изменение поля (или факт вставки/удаления) сущности.
 *
 * <p>Обычная JPA-сущность, не справочник. Таблицу создаёт миграция V4.</p>
 */
@Entity
@Table(name = "common_audit_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Имя таблицы сущности. */
    @Column(name = "entity_type", nullable = false)
    private String entityType;

    /** Идентификатор записи в строковом виде; {@code null} для операций над всей таблицей. */
    @Column(name = "entity_id")
    private String entityId;

    /** Действие: INSERT, UPDATE, DELETE или REPLACE. */
    @Column(nullable = false, length = 10)
    private String action;

    /** Изменённое поле; {@code null} для записи о вставке или удалении целиком. */
    @Column
    private String field;

    /** Прежнее значение. */
    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    /** Новое значение. */
    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    /** Идентификатор пользователя, выполнившего изменение. */
    @Column(name = "actor_id")
    private UUID actorId;

    /** Логин пользователя, выполнившего изменение. */
    @Column(name = "actor_login", length = 255)
    private String actorLogin;

    /** Момент изменения. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Проставляет момент изменения, если он не задан явно. */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
