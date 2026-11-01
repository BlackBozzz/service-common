package com.argent.servicecommon.dictionary.record;

import java.util.List;
import java.util.Map;

/**
 * Описание поля справочника для построения динамической формы.
 */
public record DictionaryFieldDefinition(
        String name,
        String label,
        String description,
        String type,
        int order,
        boolean readOnly,
        boolean visible,
        boolean required,
        String referenceCode,
        List<String> enumValues,
        List<DictionaryFieldDefinition> fields,
        List<ValidationRule> validation,
        List<ValidationRule> elementValidation
) {
    /**
     * Ограничение Bean Validation, доступное для фронтенда.
     */
    public record ValidationRule(String name, Map<String, Object> attributes) {
    }
}
