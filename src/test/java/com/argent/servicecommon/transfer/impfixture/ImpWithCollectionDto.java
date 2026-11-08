package com.argent.servicecommon.transfer.impfixture;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** DTO тестового справочника {@link ImpWithCollection}. */
public record ImpWithCollectionDto(
        @DictionaryField(label = "Идентификатор", readOnly = true) Long id,
        @DictionaryField(label = "Название") @NotBlank String name,
        @DictionaryField(label = "Метки") List<String> tags) {
}
