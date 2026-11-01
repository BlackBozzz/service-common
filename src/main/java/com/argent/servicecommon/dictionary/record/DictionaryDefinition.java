package com.argent.servicecommon.dictionary.record;

import java.util.List;

/**
 * Полное описание зарегистрированного справочника и его формы.
 */
public record DictionaryDefinition(
        String code,
        String name,
        String entityClass,
        String dtoClass,
        List<DictionaryFieldDefinition> fields
) {
}
