package com.argent.servicecommon.status.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTO справочника «Типы статусов». */
public record CommonStatusTypeDto(
        @DictionaryField(label = "Идентификатор", order = 0, readOnly = true)
        UUID id,

        @DictionaryField(label = "Код", order = 10)
        @NotBlank(message = "Код типа статуса обязателен.")
        @Size(max = 100, message = "Код не должен превышать 100 символов.")
        String code,

        @DictionaryField(label = "Название", order = 20)
        @DynamicDictionaryDisplay
        @NotBlank(message = "Название типа статуса обязательно.")
        @Size(max = 255, message = "Название не должно превышать 255 символов.")
        String name
) {
}
