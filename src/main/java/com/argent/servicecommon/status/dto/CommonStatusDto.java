package com.argent.servicecommon.status.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTO справочника «Статусы». Тип статуса передаётся вложенным DTO с идентификатором. */
public record CommonStatusDto(
        @DictionaryField(label = "Идентификатор", order = 0, readOnly = true)
        UUID id,

        @DictionaryField(label = "Тип статуса", order = 10)
        @NotNull(message = "Тип статуса обязателен.")
        CommonStatusTypeDto statusType,

        @DictionaryField(label = "Код", order = 20)
        @NotBlank(message = "Код статуса обязателен.")
        @Size(max = 100, message = "Код не должен превышать 100 символов.")
        String code,

        @DictionaryField(label = "Название", order = 30)
        @DynamicDictionaryDisplay
        @NotBlank(message = "Название статуса обязательно.")
        @Size(max = 255, message = "Название не должно превышать 255 символов.")
        String name,

        @DictionaryField(label = "Порядок", order = 40)
        @NotNull(message = "Порядок обязателен.")
        Integer sortOrder,

        @DictionaryField(label = "Начальный", order = 50)
        @NotNull(message = "Признак начального статуса обязателен.")
        Boolean initialStatus,

        @DictionaryField(label = "Конечный", order = 60)
        @NotNull(message = "Признак конечного статуса обязателен.")
        Boolean finalStatus
) {
}
