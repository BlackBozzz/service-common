package com.argent.servicecommon.status.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * DTO справочника «Переходы статусов».
 *
 * <p>Строкового поля отображения нет: модуль справочников использует идентификатор записи.</p>
 */
public record CommonStatusTransitionDto(
        @DictionaryField(label = "Идентификатор", order = 0, readOnly = true)
        UUID id,

        @DictionaryField(label = "Тип статуса", order = 10)
        @NotNull(message = "Тип статуса обязателен.")
        CommonStatusTypeDto statusType,

        @DictionaryField(label = "Из статуса", order = 20,
                description = "Пусто — переход при создании записи")
        CommonStatusDto fromStatus,

        @DictionaryField(label = "В статус", order = 30)
        @NotNull(message = "Целевой статус обязателен.")
        CommonStatusDto toStatus,

        @DictionaryField(label = "Требуемое действие", order = 40,
                description = "Действие из грантов роли; пусто — переход доступен всем")
        @Size(max = 200, message = "Действие не должно превышать 200 символов.")
        String requiredAction
) {
}
