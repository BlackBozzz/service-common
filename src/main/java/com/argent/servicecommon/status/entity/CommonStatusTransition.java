package com.argent.servicecommon.status.entity;

import com.argent.servicecommon.audit.Audited;
import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.status.dto.CommonStatusTransitionDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Допустимый переход между статусами одного типа.
 *
 * <p>{@link #fromStatus} равен {@code null} для перехода «создание записи → статус».
 * Если задано {@link #requiredAction}, переход доступен только пользователю с этим действием.
 * Библиотечный справочник: таблицу создаёт миграция V4. Правки аудируются: от переходов зависит,
 * кто может менять статусы.</p>
 */
@Entity
@Audited
@Table(
        name = "common_status_transition",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_common_status_transition",
                columnNames = {"status_type_id", "from_status_id", "to_status_id"}
        )
)
@ArgentDictionary(name = "Переходы статусов", dtoClass = CommonStatusTransitionDto.class)
@Getter
@Setter
@NoArgsConstructor
public class CommonStatusTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Тип статуса, к которому относится переход. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "status_type_id", nullable = false)
    private CommonStatusType statusType;

    /** Исходный статус; {@code null} — создание записи. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_status_id")
    private CommonStatus fromStatus;

    /** Целевой статус. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_status_id", nullable = false)
    private CommonStatus toStatus;

    /** Действие из грантов, необходимое для перехода; {@code null} — переход доступен всем. */
    @Column(name = "required_action", length = 200)
    private String requiredAction;
}
