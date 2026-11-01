package com.argent.servicecommon.dictionary.mapper.fixture.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DTO тестового справочника стран. */
public record TestCountryDto(
        @DictionaryField(label = "Идентификатор", order = 0, readOnly = true) Long id,
        @DictionaryField(label = "Страна", description = "Название страны", order = 10)
        @NotBlank @Size(min = 2, max = 80) String name
) { }
