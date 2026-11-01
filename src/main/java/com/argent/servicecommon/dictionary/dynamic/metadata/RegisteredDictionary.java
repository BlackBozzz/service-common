package com.argent.servicecommon.dictionary.dynamic.metadata;

import com.argent.servicecommon.dictionary.record.DictionaryDefinition;

/** Проверенная запись реестра и связанные с ней классы JPA и DTO. */
public record RegisteredDictionary(
        DictionaryDefinition definition,
        Class<?> entityClass,
        Class<?> dtoClass
) { }
