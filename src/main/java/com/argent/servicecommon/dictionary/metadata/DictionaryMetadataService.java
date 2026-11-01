package com.argent.servicecommon.dictionary.metadata;

import com.argent.servicecommon.dictionary.record.DictionaryDefinition;

/** Сервис описания DTO и ограничений зарегистрированного справочника. */
public interface DictionaryMetadataService {
    /** Возвращает описание полей и ограничений по коду справочника. */
    DictionaryDefinition describe(String code);
}
