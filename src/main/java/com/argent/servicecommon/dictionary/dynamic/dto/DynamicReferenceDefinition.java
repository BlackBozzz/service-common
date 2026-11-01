package com.argent.servicecommon.dictionary.dynamic.dto;

/** Описание связи со справочником для выбора существующих записей. */
public record DynamicReferenceDefinition(
        String dictionaryCode,
        String idField,
        String displayField,
        boolean multiple
) { }
