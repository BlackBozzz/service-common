package com.argent.servicecommon.dictionary.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Служебная сущность для хранения зарегистрированных справочников.
 *
 * Каждая запись соответствует одному справочнику.
 * Отсутствие записи позволяет запустить повторную генерацию его таблицы.
 */
@Entity
@Table(
        name = "common_dictionaries",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_common_dictionaries_code",
                        columnNames = "code"
                ),
                @UniqueConstraint(
                        name = "uk_common_dictionaries_entity_class",
                        columnNames = "entity_class"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class CommonDictionary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Уникальный код справочника.
     * Совпадает с названием таблицы из @Table.
     */
    @Column(nullable = false, length = 255)
    private String code;

    /**
     * Отображаемое название справочника.
     */
    @Column(nullable = false, length = 255)
    private String name;

    /**
     * Простое имя класса сущности без пакета.
     */
    @Column(name = "entity_class", nullable = false, length = 255)
    private String entityClass;

    /**
     * Полное имя DTO-класса справочника.
     */
    @Column(name = "dto_class", nullable = false, length = 500)
    private String dtoClass;

    /**
     * Дата и время последней успешной генерации структуры справочника.
     */
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
}
