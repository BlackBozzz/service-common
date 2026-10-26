package com.argent.servicecommon.dictionary.service;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.dto.DictionaryMetadataDto;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Type;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Сервис обнаружения справочников среди зарегистрированных JPA-сущностей.
 */
@RequiredArgsConstructor
public class DictionaryScannerService {
    private final EntityManagerFactory entityManagerFactory;

    /**
     * Находит все JPA-сущности, помеченные аннотацией @ArgentDictionary.
     *
     * @return список метаданных обнаруженных справочников
     */
    public List<DictionaryMetadataDto> scan() {
        return entityManagerFactory.getMetamodel()
                .getEntities()
                .stream()
                .map(Type::getJavaType)
                .filter(entityClass ->
                        entityClass.isAnnotationPresent(ArgentDictionary.class)
                )
                .map(this::extractMetadata)
                .toList();
    }

    /**
     * Извлекает метаданные справочника из аннотаций сущности.
     */
    private DictionaryMetadataDto extractMetadata(Class<?> entityClass) {
        ArgentDictionary dictionary = entityClass.getAnnotation(ArgentDictionary.class);
        Table table = entityClass.getAnnotation(Table.class);

        if (table == null || table.name().isBlank()) {
            throw new IllegalStateException(
                    "Для справочника '%s' необходимо явно указать @Table(name = \"...\")"
                            .formatted(entityClass.getSimpleName())
            );
        }

        return new DictionaryMetadataDto(
                table.name(),
                dictionary.name(),
                entityClass,
                dictionary.dtoClass()
        );
    }
}
