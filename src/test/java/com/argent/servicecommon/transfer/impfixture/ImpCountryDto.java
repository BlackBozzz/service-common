package com.argent.servicecommon.transfer.impfixture;

import com.argent.servicecommon.dictionary.annotation.DictionaryField;
import com.argent.servicecommon.dictionary.dynamic.annotation.DynamicDictionaryDisplay;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/** DTO тестового справочника {@link ImpCountry}; регион передаётся вложенным DTO с идентификатором. */
public record ImpCountryDto(
        @DictionaryField(label = "Идентификатор", readOnly = true) Long id,
        @DictionaryField(label = "Название") @DynamicDictionaryDisplay @NotBlank String name,
        @DictionaryField(label = "Код") String code,
        @DictionaryField(label = "Население") @PositiveOrZero Integer population,
        @DictionaryField(label = "Площадь") BigDecimal area,
        @DictionaryField(label = "Дата основания") LocalDate founded,
        @DictionaryField(label = "Активна") Boolean active,
        @DictionaryField(label = "Регион") ImpRegionDto region) {
}
