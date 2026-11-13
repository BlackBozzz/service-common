package com.argent.servicecommon.dictionary.dynamic.dto;

import java.util.List;

/** Динамическая форма с указанием вида сущности и разрешения на редактирование. */
public record DynamicDictionarySchema(
        String code, String name, String idField, String displayField,
        List<DynamicFieldDefinition> fields, String kind, String group, boolean editable
) {
    /** Совместимость с прежним конструктором схемы. */
    public DynamicDictionarySchema(String code, String name, String idField,
                                   String displayField, List<DynamicFieldDefinition> fields) {
        this(code, name, idField, displayField, fields, "dictionary", "Справочники", true);
    }
}
