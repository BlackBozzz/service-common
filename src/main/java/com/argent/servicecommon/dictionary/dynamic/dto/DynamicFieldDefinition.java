package com.argent.servicecommon.dictionary.dynamic.dto;

import com.argent.servicecommon.dictionary.record.DictionaryFieldDefinition;

import java.util.List;
import java.util.Map;

/**
 * Полное описание поля динамической формы.
 */
public record DynamicFieldDefinition(
        String name,
        String label,
        String description,
        String type,
        String control,
        String placeholder,
        String help,
        int order,
        boolean visible,
        boolean readOnly,
        boolean required,
        Integer minLength,
        Integer maxLength,
        Integer minItems,
        Integer maxItems,
        String minimum,
        String maximum,
        String pattern,
        List<String> enumValues,
        DynamicReferenceDefinition reference,
        List<DynamicFieldDefinition> fields,
        List<DictionaryFieldDefinition.ValidationRule> validation,
        List<DictionaryFieldDefinition.ValidationRule> elementValidation,
        Map<String, String> enumLabels
) {
    /**
     * Сохраняет совместимость существующих вызовов, где ещё не передаются подписи enum.
     */
    public DynamicFieldDefinition(
            String name, String label, String description, String type, String control,
            String placeholder, String help, int order, boolean visible, boolean readOnly,
            boolean required, Integer minLength, Integer maxLength, Integer minItems,
            Integer maxItems, String minimum, String maximum, String pattern,
            List<String> enumValues, DynamicReferenceDefinition reference,
            List<DynamicFieldDefinition> fields,
            List<DictionaryFieldDefinition.ValidationRule> validation,
            List<DictionaryFieldDefinition.ValidationRule> elementValidation
    ) {
        this(name, label, description, type, control, placeholder, help, order,
                visible, readOnly, required, minLength, maxLength, minItems, maxItems,
                minimum, maximum, pattern, enumValues, reference, fields,
                validation, elementValidation, Map.of());
    }
}

