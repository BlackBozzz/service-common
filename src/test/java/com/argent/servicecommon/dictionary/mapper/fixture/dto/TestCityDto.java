package com.argent.servicecommon.dictionary.mapper.fixture.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Полный DTO города. Вложенные объекты связей не изменяются при сохранении города. */
public record TestCityDto(
        @DictionaryField(label = "Идентификатор", readOnly = true) Long id,
        @DictionaryField(label = "Город", order = 10)
        @NotBlank @Size(min = 2, max = 80) String name,
        @Min(0) @Max(100000000) int population,
        @DictionaryField(label = "Страна", order = 20)
        @NotNull @Valid TestCountryDto country,
        Set<@Valid TestTagDto> tags,
        Set<@NotBlank String> aliases,
        TestAddressDto address
) { }
