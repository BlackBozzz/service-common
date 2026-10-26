package com.argent.servicecommon.dictionary.generator.postgresql;

import com.argent.servicecommon.dictionary.dto.DictionaryMetadataDto;
import com.argent.servicecommon.dictionary.exception.DictionaryRegistrationException;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Строит SQL штатным механизмом Hibernate SchemaCreator.
 *
 * Здесь нет собственного преобразования аннотаций JPA в SQL. Наш код лишь
 * выбирает объекты новых справочников и проверяет зависимости от старых таблиц.
 */
@RequiredArgsConstructor
public class HibernateDictionaryDdlBuilder {

    private final EntityManagerFactory entityManagerFactory;
    private final JdbcTemplate jdbc;

    /**
     * Формирует DDL для всех новых справочников одним пакетом.
     * Объекты остальных Entity включены в модель только как зависимости.
     */
    public List<String> build(List<DictionaryMetadataDto> newDictionaries,
                              String schema) {
        if (newDictionaries.isEmpty()) {
            return List.of();
        }

        SessionFactoryImplementor factory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        Map<String, Object> settings = new HashMap<>();
        entityManagerFactory.getProperties().forEach((key, value) -> settings.put(key.toString(), value));
        settings.put("hibernate.dialect", factory.getJdbcServices().getDialect().getClass().getName());
        settings.put("hibernate.default_schema", schema);
        settings.put("hibernate.hbm2ddl.auto", "none");
        settings.put("jakarta.persistence.schema-generation.database.action", "none");
        settings.put("hibernate.hbm2ddl.import_files", "");

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            entityManagerFactory.getMetamodel().getEntities().forEach(
                    entity -> sources.addAnnotatedClass(entity.getJavaType())
            );
            Metadata metadata = sources.buildMetadata();

            // Собираем именно таблицы, принадлежащие новым Entity и их коллекциям.
            Set<String> newClasses = new HashSet<>();
            for (DictionaryMetadataDto dictionary : newDictionaries) {
                newClasses.add(dictionary.entityClass().getName());
            }
            Set<Table> newTables = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Table> allEntityTables = Collections.newSetFromMap(new IdentityHashMap<>());
            Map<String, PersistentClass> byClass = new HashMap<>();

            for (PersistentClass entity : metadata.getEntityBindings()) {
                if (entity.getClassName() == null) {
                    continue;
                }
                byClass.put(entity.getClassName(), entity);
                allEntityTables.add(entity.getTable());
                for (var join : entity.getJoins()) {
                    allEntityTables.add(join.getTable());
                }
            }

            for (DictionaryMetadataDto dictionary : newDictionaries) {
                PersistentClass entity = byClass.get(dictionary.entityClass().getName());
                if (entity == null) {
                    throw failure("Не найдена Hibernate-модель справочника: " + dictionary.code());
                }
                if (!dictionary.code().equals(entity.getTable().getName())) {
                    throw failure("Имя таблицы Hibernate '%s' не совпало с кодом справочника '%s'. "
                            .formatted(entity.getTable().getName(), dictionary.code())
                            + "Проверьте настройки PhysicalNamingStrategy.");
                }
                newTables.add(entity.getTable());
                for (var join : entity.getJoins()) {
                    newTables.add(join.getTable());
                }
            }

            // @ElementCollection, @ManyToMany и @JoinTable могут требовать отдельных таблиц.
            // Но таблица однонаправленного @OneToMany с @JoinColumn может быть таблицей
            // другой Entity: её не разрешаем незаметно менять.
            for (var collection : metadata.getCollectionBindings()) {
                if (!newClasses.contains(collection.getOwnerEntityName()) || collection.isInverse()) {
                    continue;
                }
                Table collectionTable = collection.getCollectionTable();
                if (collectionTable != null && !allEntityTables.contains(collectionTable)) {
                    newTables.add(collectionTable);
                }
            }

            // Не создаём таблицы, принадлежащие другой Entity, даже при совпадении имён.
            for (PersistentClass entity : metadata.getEntityBindings()) {
                if (entity.getClassName() != null && !newClasses.contains(entity.getClassName())) {
                    if (newTables.contains(entity.getTable())) {
                        throw failure("Новый справочник использует таблицу существующей Entity: "
                                + entity.getTable().getName());
                    }
                }
            }

            // Проверки выполняются до генерации и до исполнения хотя бы одной команды.
            for (Table table : newTables) {
                checkName(table.getName());
                checkSchema(table.getSchema(), schema);
                if (exists(schema, table.getName())) {
                    throw failure("Таблица '%s.%s' уже существует. Удалите её вручную, если хотите создать заново."
                            .formatted(schema, table.getName()));
                }
            }

            // Запрещаем молча добавлять внешний ключ в уже существующую таблицу.
            // Это особенно важно для @OneToMany с @JoinColumn на стороне старой Entity.
            for (PersistentClass entity : metadata.getEntityBindings()) {
                verifyForeignKeys(entity.getTable(), newTables);
                for (var join : entity.getJoins()) {
                    verifyForeignKeys(join.getTable(), newTables);
                }
            }
            for (var namespace : metadata.getDatabase().getNamespaces()) {
                for (Table table : namespace.getTables()) {
                    verifyForeignKeys(table, newTables);
                }
            }

            // Выборочно генерировать UDT (включая PostgreSQL NAMED_ENUM) через
            // SchemaFilter нельзя: Hibernate создаёт их вне фильтра таблиц.
            // Стандартный @Enumerated STRING/ORDINAL работает, а отдельные
            // именованные типы требуют специального безопасного обработчика.
            for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
                if (!namespace.getUserDefinedTypes().isEmpty()) {
                    throw failure("Обнаружены пользовательские SQL-типы (например, PostgreSQL NAMED_ENUM). "
                            + "Их выборочная генерация рядом с существующими таблицами пока запрещена.");
                }
            }
            if (!metadata.getDatabase().getAuxiliaryDatabaseObjects().isEmpty()) {
                throw failure("Обнаружены вспомогательные SQL-объекты Hibernate. "
                        + "Без явного определения владельца их создание запрещено.");
            }
            if (!metadata.getDatabase().getInitCommands().isEmpty()) {
                throw failure("Обнаружены команды инициализации Hibernate. Генератор не выполняет произвольные SQL-команды.");
            }

            // Привязываем последовательности к фактическим генераторам новых Entity.
            // Так последовательности других частей приложения не попадут в наш DDL.
            Set<String> requestedSequences = new HashSet<>();
            for (DictionaryMetadataDto dictionary : newDictionaries) {
                var generator = factory.getMappingMetamodel()
                        .getEntityDescriptor(dictionary.entityClass())
                        .getEntityPersister()
                        .getGenerator();
                if (generator instanceof SequenceStyleGenerator sequenceGenerator) {
                    var structure = sequenceGenerator.getDatabaseStructure();
                    if (!structure.isPhysicalSequence()) {
                        throw failure("Табличный генератор ID пока не поддерживается: " + dictionary.code());
                    }
                    var sequenceName = structure.getPhysicalName();
                    if (sequenceName == null) {
                        throw failure("Не удалось определить имя последовательности: " + dictionary.code());
                    }
                    checkSchema(sequenceName.getSchemaName() == null ? null
                            : sequenceName.getSchemaName().getText(), schema);
                    String name = sequenceName.getObjectName().getText();
                    checkName(name);
                    if (exists(schema, name)) {
                        throw failure("Последовательность '%s.%s' уже существует. "
                                .formatted(schema, name)
                                + "Совместное использование генератора требует отдельного согласования.");
                    }
                    requestedSequences.add(name);
                }
            }

            Set<String> availableSequences = new HashSet<>();
            for (var namespace : metadata.getDatabase().getNamespaces()) {
                for (Sequence sequence : namespace.getSequences()) {
                    availableSequences.add(sequence.getName().getSequenceName().getText());
                }
            }
            if (!availableSequences.containsAll(requestedSequences)) {
                throw failure("Последовательности новой Entity отличаются от модели Hibernate. "
                        + "Проверьте стратегию именования и настройки генераторов.");
            }

            SchemaFilter filter = new SchemaFilter() {
                @Override
                public boolean includeNamespace(Namespace namespace) {
                    var identifier = namespace.getPhysicalName().schema();
                    return identifier == null || schema.equals(identifier.getText());
                }

                @Override
                public boolean includeTable(Table table) {
                    return newTables.contains(table);
                }

                @Override
                public boolean includeSequence(Sequence sequence) {
                    return requestedSequences.contains(sequence.getName().getSequenceName().getText());
                }
            };

            // Hibernate сам создаёт PK, UNIQUE, индексы, FK, JOIN TABLE и типы колонок.
            // Команды получаем как список, без ручного разбора и разделения по ';'.
            List<String> commands = new SchemaCreatorImpl(registry, filter)
                    .generateCreationCommands(metadata, false);
            if (commands.isEmpty()) {
                throw failure("Hibernate не сформировал DDL для новых справочников.");
            }
            return List.copyOf(commands);
        } catch (DictionaryRegistrationException e) {
            throw e;
        } catch (Exception e) {
            throw failure("Не удалось сформировать DDL Hibernate: " + e.getMessage());
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** Проверяет, не требуется ли ALTER TABLE уже существующей таблицы. */
    private void verifyForeignKeys(Table table, Set<Table> newTables) {
        if (table == null || newTables.contains(table)) {
            return;
        }
        for (var foreignKey : table.getForeignKeyCollection()) {
            if (newTables.contains(foreignKey.getReferencedTable())) {
                throw failure("Связь с новым справочником требует изменения существующей таблицы '"
                        + table.getName() + "'. Выполните эту миграцию через Flyway.");
            }
        }
    }

    /** Проверяет существование конкретного объекта в выбранной схеме PostgreSQL. */
    private boolean exists(String schema, String name) {
        Boolean exists = jdbc.queryForObject(
                "select to_regclass(format('%I.%I', ?, ?)) is not null",
                Boolean.class,
                schema,
                name
        );
        return Boolean.TRUE.equals(exists);
    }

    /** Ограничиваем область работы простыми и однозначными именами объектов. */
    private void checkName(String name) {
        if (name == null || !name.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw failure("Недопустимое физическое имя SQL-объекта: " + name);
        }
    }

    /** Не разрешаем генерацию объектов в чужую схему. */
    private void checkSchema(String actual, String expected) {
        if (actual != null && !actual.equals(expected)) {
            throw failure("Объект относится к схеме '%s', но разрешена только '%s'."
                    .formatted(actual, expected));
        }
    }

    private DictionaryRegistrationException failure(String message) {
        return new DictionaryRegistrationException(message);
    }
}
