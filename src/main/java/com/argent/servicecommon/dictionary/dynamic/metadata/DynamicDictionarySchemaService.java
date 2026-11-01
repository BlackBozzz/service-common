package com.argent.servicecommon.dictionary.dynamic.metadata;

import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySchema;

/** Формирует структуру динамической формы и выбора связанных записей. */
public interface DynamicDictionarySchemaService {
    DynamicDictionarySchema describe(String code);
}
