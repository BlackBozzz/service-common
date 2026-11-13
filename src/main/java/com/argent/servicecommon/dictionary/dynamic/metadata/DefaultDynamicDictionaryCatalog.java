package com.argent.servicecommon.dictionary.dynamic.metadata;

import com.argent.servicecommon.dictionary.annotation.ArgentDataTable;
import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.dynamic.dto.DynamicDictionarySummary;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import com.argent.servicecommon.dictionary.record.DictionaryDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Общий каталог справочников и сущностей приложения; не создаёт пользовательские таблицы. */
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
        List<DynamicDictionarySummary> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        List<DynamicDictionarySummary> registered = jdbc.query(
                "select code, name from common_dictionaries order by name, code",
                (rs, index) -> new DynamicDictionarySummary(rs.getString("code"), rs.getString("name")));
        for (DynamicDictionarySummary entry : registered) {
            Class<?> entityClass = findAnnotated(entry.code(), false);
            ArgentDictionary dictionary = entityClass == null ? null
                    : entityClass.getAnnotation(ArgentDictionary.class);
            String group = dictionary == null ? "Справочники" : dictionary.group();
            codes.add(entry.code());
            result.add(new DynamicDictionarySummary(entry.code(), entry.name(), "dictionary", group));
        }
        for (var entityType : entityManager.getMetamodel().getEntities()) {
            Class<?> entityClass = entityType.getJavaType();
            ArgentDataTable tableDefinition = entityClass.getAnnotation(ArgentDataTable.class);
            if (tableDefinition == null) continue;
            if (entityClass.isAnnotationPresent(ArgentDictionary.class)) {
                throw new IllegalStateException("Нельзя совместить @ArgentDataTable и @ArgentDictionary: "
                        + entityClass.getName());
            }
            Table table = requiredTable(entityClass);
            if (!codes.add(table.name())) {
                throw new IllegalStateException("Повторяющийся код таблицы в каталоге: " + table.name());
            }
            result.add(new DynamicDictionarySummary(table.name(), tableDefinition.name(), "table",
                    tableDefinition.group()));
        }
        result.sort(Comparator.comparing(DynamicDictionarySummary::group)
                .thenComparing(DynamicDictionarySummary::name)
                .thenComparing(DynamicDictionarySummary::code));
        return List.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public RegisteredDictionary require(String code) {
        if (code == null || code.isBlank()) {
            throw new DynamicDictionaryBadRequestException("Код таблицы не может быть пустым.");
        }
        // Сначала проверяем, что таблица известна реестру либо объявлена в @ArgentDataTable.
        Class<?> entityClass = findAnnotated(code, true);
        if (entityClass == null) {
            if (registered(code)) throw new IllegalStateException(
                    "Зарегистрированный справочник '%s' отсутствует в JPA.".formatted(code));
            throw new DynamicDictionaryNotFoundException(code);
        }
        ArgentDictionary dictionary = entityClass.getAnnotation(ArgentDictionary.class);
        ArgentDataTable dataTable = entityClass.getAnnotation(ArgentDataTable.class);
        if (dictionary != null && !registered(code)) throw new DynamicDictionaryNotFoundException(code);
        if (dictionary == null && dataTable == null) throw new DynamicDictionaryNotFoundException(code);
        Class<?> dtoClass = dictionary != null ? dictionary.dtoClass() : dataTable.dtoClass();
        DictionaryDefinition definition = metadata.describe(code);
        if (!definition.entityClass().equals(entityClass.getName())
                || !definition.dtoClass().equals(dtoClass.getName())) {
            throw new IllegalStateException("Метаданные DTO не соответствуют JPA: " + code);
        }
        return new RegisteredDictionary(definition, entityClass, dtoClass);
    }

    private boolean registered(String code) {
        Integer count = jdbc.queryForObject("select count(*) from common_dictionaries where code = ?",
                Integer.class, code);
        return count != null && count > 0;
    }

    private Class<?> findAnnotated(String code, boolean includeDataTable) {
        for (var type : entityManager.getMetamodel().getEntities()) {
            Class<?> entityClass = type.getJavaType();
            Table table = entityClass.getAnnotation(Table.class);
            if (table == null || !code.equals(table.name())) continue;
            if (entityClass.isAnnotationPresent(ArgentDictionary.class)
                    || includeDataTable && entityClass.isAnnotationPresent(ArgentDataTable.class)) {
                return entityClass;
            }
        }
        return null;
    }

    private Table requiredTable(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table == null || !table.name().matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalStateException("@ArgentDataTable требует @Table с допустимым именем: "
                    + entityClass.getName());
        }
        return table;
    }
}
