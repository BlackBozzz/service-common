package com.argent.servicecommon.dictionary.dynamic.metadata;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import com.argent.servicecommon.dictionary.record.DictionaryDefinition;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Objects;

/**
 * Получает справочники из общей таблицы реестра и перепроверяет типы через JPA.
 * Не использует Class.forName для данных, поступающих с клиента.
 */
public class DefaultDynamicDictionaryCatalog implements DynamicDictionaryCatalog {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final DictionaryMetadataService metadata;

    public DefaultDynamicDictionaryCatalog(JdbcTemplate jdbc, EntityManager entityManager,
                                           DictionaryMetadataService metadata) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.entityManager = Objects.requireNonNull(entityManager);
        this.metadata = Objects.requireNonNull(metadata);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DynamicDictionarySummary> list() {
        return jdbc.query(
                "select code, name from common_dictionaries order by name, code",
                (rs, index) -> new DynamicDictionarySummary(
                        rs.getString("code"), rs.getString("name")));
    }

    @Override
    @Transactional(readOnly = true)
    public RegisteredDictionary require(String code) {
        if (code == null || code.isBlank()) {
            throw new DynamicDictionaryBadRequestException("Код справочника не может быть пустым.");
        }
        // Сначала проверяем наличие регистрации, затем — согласованность с JPA.
        Integer count = jdbc.queryForObject(
                "select count(*) from common_dictionaries where code = ?",
                Integer.class, code);
        if (count == null || count == 0) {
            throw new DynamicDictionaryNotFoundException(code);
        }
        DictionaryDefinition definition = metadata.describe(code);
        for (var type : entityManager.getMetamodel().getEntities()) {
            Class<?> entityClass = type.getJavaType();
            if (!entityClass.getName().equals(definition.entityClass())) continue;
            ArgentDictionary annotation = entityClass.getAnnotation(ArgentDictionary.class);
            if (annotation == null || !annotation.dtoClass().getName().equals(definition.dtoClass())) {
                throw new IllegalStateException(
                        "Метаданные DTO справочника '%s' не совпадают с JPA.".formatted(code));
            }
            return new RegisteredDictionary(definition, entityClass, annotation.dtoClass());
        }
        throw new IllegalStateException(
                "Сущность зарегистрированного справочника '%s' отсутствует в JPA.".formatted(code));
    }
}
