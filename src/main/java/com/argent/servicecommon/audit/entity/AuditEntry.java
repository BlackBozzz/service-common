package com.argent.servicecommon.audit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Строка журнала аудита: изменение поля или факт действия над записью; таблицу создаёт миграция V4. */
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

    /** INSERT, UPDATE, DELETE, REPLACE (импорт), ATTACH или DETACH (вложения). */
    @Column(nullable = false, length = 10)
    private String action;

    /** Изменённое поле; {@code null} для записи о вставке или удалении целиком. */
    @Column
    private String field;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_login", length = 255)
    private String actorLogin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
