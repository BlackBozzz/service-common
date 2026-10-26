package com.argent.servicecommon.dictionary.dto;

/**
 * Метаданные справочника, полученные из аннотаций Java-сущности.
 *
 * Используются для регистрации справочников и проверки
 * их соответствия записям в базе данных.
 *
 * @param code уникальный код справочника из @Table
 * @param name отображаемое название справочника
 * @param entityClass Java-класс сущности
 * @param dtoClass Java-класс DTO
 */
public record DictionaryMetadataDto(
        String code,
        String name,
        Class<?> entityClass,
        Class<?> dtoClass
) {
}
