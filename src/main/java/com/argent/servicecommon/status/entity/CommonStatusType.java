package com.argent.servicecommon.status.entity;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.status.dto.CommonStatusTypeDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Тип статуса — набор статусов одной сущности приложения (например, {@code ORDER_STATUS}).
 *
 * <p>Библиотечный справочник: таблицу создаёт миграция V4, запись реестра вставляет та же миграция.</p>
 */
@Entity
@Table(name = "common_status_type")
@ArgentDictionary(name = "Типы статусов", dtoClass = CommonStatusTypeDto.class)
@Getter
@Setter
@NoArgsConstructor
public class CommonStatusType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Уникальный код типа, по которому приложение обращается к статусной машине. */
    @Column(nullable = false, unique = true, length = 100)
    private String code;

    /** Отображаемое название типа. */
    @Column(nullable = false)
    private String name;
}
