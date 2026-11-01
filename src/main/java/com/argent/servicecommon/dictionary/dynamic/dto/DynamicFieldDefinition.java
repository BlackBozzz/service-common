package com.argent.servicecommon.dictionary.dynamic.dto;

import com.argent.servicecommon.dictionary.record.DictionaryFieldDefinition;

import java.util.List;

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
        List<DictionaryFieldDefinition.ValidationRule> elementValidation
) {
}
