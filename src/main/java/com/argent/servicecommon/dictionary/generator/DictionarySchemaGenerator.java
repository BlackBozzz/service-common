package com.argent.servicecommon.dictionary.generator;

import com.argent.servicecommon.dictionary.dto.DictionaryMetadataDto;
import java.util.List;

/**
 * Создаёт физическую структуру всех новых справочников одной операцией.
 *
 * Пакетная генерация необходима для двусторонних и циклических связей:
 * сначала Hibernate создаёт все таблицы, затем внешние ключи.
 */
public interface DictionarySchemaGenerator {

    /**
     * Генерирует структуру новых справочников с учётом всей модели приложения.
     * Вызывающий сервис обязан открыть одну транзакцию для DDL и регистрации.
     *
     * @param newDictionaries справочники без записей в реестре
         */
    void generateBatch(List<DictionaryMetadataDto> newDictionaries);
}
