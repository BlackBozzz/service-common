package com.argent.servicecommon.dictionary.dynamic.dto;

import java.util.List;

/** Описание справочника и формы для универсального фронтенда. */
public record DynamicDictionarySchema(
        String code,
        String name,
        String idField,
        String displayField,
        List<DynamicFieldDefinition> fields
) { }
