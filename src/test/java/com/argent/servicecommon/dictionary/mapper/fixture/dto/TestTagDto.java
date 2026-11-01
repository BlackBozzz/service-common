package com.argent.servicecommon.dictionary.mapper.fixture.dto;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import jakarta.validation.constraints.NotBlank;

/** DTO категории. */
public record TestTagDto(
        @DictionaryField(readOnly = true) Long id,
        @NotBlank String name
) { }
