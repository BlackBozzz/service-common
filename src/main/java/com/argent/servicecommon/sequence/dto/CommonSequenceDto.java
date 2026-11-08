package com.argent.servicecommon.sequence.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import com.argent.servicecommon.sequence.SequencePattern;
import com.argent.servicecommon.sequence.entity.ResetPeriod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * DTO справочника «Счётчики номеров».
 *
 * <p>{@code periodKey} и {@code counter} только для чтения: форма админки их показывает, но не
 * записывает, поэтому сохранение формы не откатывает счётчик.</p>
 */
public record CommonSequenceDto(
        @DictionaryField(label = "Идентификатор", order = 0, readOnly = true)
        UUID id,

        @DictionaryField(label = "Код", order = 10)
        @DynamicDictionaryDisplay
        @NotBlank(message = "Код счётчика обязателен.")
        @Size(max = 100, message = "Код не должен превышать 100 символов.")
        String code,

        @DictionaryField(label = "Шаблон номера", order = 20,
                description = "Шаблон String.format: %1$s — период, %2$d — счётчик, "
                        + "например ORD-%1$s-%2$05d")
        @NotBlank(message = "Шаблон номера обязателен.")
        @Size(max = 255, message = "Шаблон не должен превышать 255 символов.")
        @SequencePattern
        String pattern,

        @DictionaryField(label = "Период сброса", order = 30)
        @NotNull(message = "Период сброса обязателен.")
        ResetPeriod resetPeriod,

        @DictionaryField(label = "Текущий период", order = 40, readOnly = true)
        String periodKey,

        @DictionaryField(label = "Последнее значение", order = 50, readOnly = true,
                description = "Меняется только при выдаче номера; продолжить нумерацию — SQL UPDATE, см. docs/reference.md")
        Long counter
) {
}
