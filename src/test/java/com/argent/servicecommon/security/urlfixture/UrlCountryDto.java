package com.argent.servicecommon.security.urlfixture;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import jakarta.validation.constraints.NotBlank;

/** DTO тестового справочника {@link UrlCountry}. */
public record UrlCountryDto(
        @DictionaryField(label = "Идентификатор", readOnly = true) Long id,
        @DynamicDictionaryDisplay @NotBlank String name) {
}
