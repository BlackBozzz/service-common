package com.argent.servicecommon.transfer.impfixture;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import jakarta.validation.constraints.NotBlank;

/** DTO тестового справочника {@link ImpRegion}. */
public record ImpRegionDto(
        @DictionaryField(label = "Идентификатор", readOnly = true) Long id,
        @DictionaryField(label = "Название") @DynamicDictionaryDisplay @NotBlank String name) {
}
