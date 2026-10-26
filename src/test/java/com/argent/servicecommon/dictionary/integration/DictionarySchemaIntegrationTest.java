package com.argent.servicecommon.dictionary.integration;

import com.argent.servicecommon.dictionary.dto.DictionaryMetadataDto;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.exception.DictionaryRegistrationException;
import com.argent.servicecommon.dictionary.generator.DictionarySchemaGenerator;
import com.argent.servicecommon.dictionary.integration.dto.ItCountryDto;
import com.argent.servicecommon.dictionary.integration.dto.ItRollbackFixtureDto;
import com.argent.servicecommon.dictionary.integration.entity.ItCountry;
import com.argent.servicecommon.dictionary.integration.entity.ItRollbackFixture;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.dictionary.service.DictionaryRegistrationService;
import com.argent.servicecommon.dictionary.service.DictionaryScannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверка реального DDL, сформированного Hibernate и выполненного библиотекой.
 * Все проверки идут по pg_catalog / information_schema временной PostgreSQL.
 * Рабочая база main_backend и существующая dict_country не затрагиваются.
 */
@SpringBootTest(
        classes = DictionarySchemaIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Testcontainers
class DictionarySchemaIntegrationTest {
    @Autowired
    private DictionaryScannerService scannerService;

    /**
     * Проверяет, что Hibernate обнаруживает все тестовые
     * справочники, помеченные аннотацией ArgentDictionary.
     */
    @Test
    void shouldDiscoverTestDictionaries() {

        var dictionaries = scannerService.scan();

        System.out.println("Обнаруженные справочники:");

        dictionaries.forEach(dictionary ->
                System.out.println(
                        dictionary.code() + " -> "
                                + dictionary.entityClass().getName()
                )
        );

        assertThat(dictionaries)
                .extracting(DictionaryMetadataDto::code)
                .contains("it_countries", "it_cities");
    }

    /**
     * Один временный PostgreSQL на тестовый класс. Скрипт инициализации
     * создаёт it_schema до инициализации Spring DataSource и Flyway.
     */
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("argent_dictionary_tests")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/dictionary-test-init.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        /**
         * Подключаем тестовое приложение к отдельной схеме PostgreSQL.
         *
         * JDBC URL Testcontainers уже может содержать параметры,
         * поэтому дополнительные параметры присоединяем через &.
         */
        registry.add(
                "spring.datasource.url",
                () -> postgres.getJdbcUrl() + "&currentSchema=it_schema"
        );
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/common");
        registry.add("spring.flyway.schemas", () -> "it_schema");
        registry.add("spring.flyway.default-schema", () -> "it_schema");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "it_schema");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.jpa.properties.hibernate.type.prefer_native_enum_types", () -> "false");
    }

    /**
     * Обозначаем тестовые Entity явно, чтобы старые демонстрационные классы
     * из пакета dictionary.testentity не попадали в этот Spring-контекст.
     * Репозиторий common_dictionaries берётся из настоящей библиотеки.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CommonDictionary.class, ItCountry.class})
    @EnableJpaRepositories(basePackageClasses = CommonDictionaryRepository.class)
    static class TestApplication {
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CommonDictionaryRepository repository;

    @Autowired
    private DictionaryRegistrationService registration;

    @Autowired
    private DictionarySchemaGenerator generator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Проверяем, что Spring Boot + Flyway зарегистрировали все тестовые справочники. */
    @Test
    void shouldRegisterAllDictionaries() {
        assertThat(repository.findAll().stream().map(CommonDictionary::getCode).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "it_countries", "it_cities", "it_categories", "it_city_profiles",
                        "it_documents", "it_regions", "it_groups", "it_cycle_a", "it_cycle_b"
                ));
        assertThat(repository.findAll()).allSatisfy(row -> {
            assertThat(row.getGeneratedAt()).isNotNull();
            assertThat(row.getDtoClass()).contains(".integration.dto.");
        });
    }

    /** Проверяем IDENTITY, NOT NULL, длину varchar и UNIQUE на колонке. */
    @Test
    void shouldCreateCountryWithIdentityAndUniqueCode() {
        assertThat(exists("it_countries")).isTrue();
        assertThat(columnType("it_countries", "id")).isEqualTo("bigint");
        assertThat(columnValue("it_countries", "id", "is_identity")).isEqualTo("YES");
        assertThat(columnLength("it_countries", "code")).isEqualTo(3);
        assertThat(columnLength("it_countries", "name")).isEqualTo(150);
        assertThat(columnValue("it_countries", "code", "is_nullable")).isEqualTo("NO");
        assertThat(constraintCount("it_countries", "u")).isGreaterThanOrEqualTo(1);
    }

    /** Проверяем FK ManyToOne и составную уникальность пары country_id/code. */
    @Test
    void shouldCreateCityWithReferencesAndCompositeUnique() {
        assertThat(exists("it_cities")).isTrue();
        assertThat(foreignKeyCount("it_cities", "it_countries")).isGreaterThanOrEqualTo(1);
        assertThat(constraintExists("uq_it_cities_country_code", "u")).isTrue();
        assertThat(indexExists("ix_it_cities_name")).isTrue();
    }

    /** Проверяем колонки Embedded и отсутствие физической колонки Formula. */
    @Test
    void shouldCreateEmbeddedColumnsButNotFormula() {
        assertThat(columnExists("it_cities", "street")).isTrue();
        assertThat(columnExists("it_cities", "postal_code")).isTrue();
        assertThat(columnExists("it_cities", "upper_name")).isFalse();
    }

    /** Проверяем обе дополнительные таблицы и их внешние ключи. */
    @Test
    void shouldCreateElementCollectionAndManyToManyTables() {
        assertThat(exists("it_city_aliases")).isTrue();
        assertThat(columnExists("it_city_aliases", "alias")).isTrue();
        assertThat(foreignKeyCount("it_city_aliases", "it_cities")).isGreaterThanOrEqualTo(1);
        assertThat(exists("it_city_categories")).isTrue();
        assertThat(foreignKeyCount("it_city_categories", "it_cities")).isGreaterThanOrEqualTo(1);
        assertThat(foreignKeyCount("it_city_categories", "it_categories")).isGreaterThanOrEqualTo(1);
    }

    /** Проверяем отдельную JoinTable для однонаправленной OneToMany. */
    @Test
    void shouldCreateOneToManyJoinTable() {
        assertThat(exists("it_group_cities")).isTrue();
        assertThat(foreignKeyCount("it_group_cities", "it_groups")).isGreaterThanOrEqualTo(1);
        assertThat(foreignKeyCount("it_group_cities", "it_cities")).isGreaterThanOrEqualTo(1);
    }

    /** Проверяем уникальный внешний ключ владеющей стороны OneToOne. */
    @Test
    void shouldCreateOneToOneUniqueForeignKey() {
        assertThat(exists("it_city_profiles")).isTrue();
        assertThat(foreignKeyCount("it_city_profiles", "it_cities")).isGreaterThanOrEqualTo(1);
        assertThat(constraintCount("it_city_profiles", "u")).isGreaterThanOrEqualTo(1);
    }

    /** Проверяем PostgreSQL UUID, SEQUENCE с шагом 5 и ASSIGNED. */
    @Test
    void shouldSupportUuidSequenceAndAssignedIdentifiers() {
        assertThat(columnType("it_categories", "id")).isEqualTo("uuid");
        assertThat(columnValue("it_categories", "id", "is_identity")).isEqualTo("NO");
        assertThat(exists("it_document_id_seq")).isTrue();
        Integer increment = jdbc.queryForObject(
                "select increment_by::integer from pg_sequences where schemaname = 'it_schema' and sequencename = 'it_document_id_seq'",
                Integer.class
        );
        assertThat(increment).isEqualTo(5);
        Long start = jdbc.queryForObject(
                "select start_value from pg_sequences where schemaname = 'it_schema' and sequencename = 'it_document_id_seq'",
                Long.class
        );
        assertThat(start).isEqualTo(11L);
        assertThat(columnValue("it_regions", "id", "is_identity")).isEqualTo("NO");
        assertThat(columnType("it_regions", "id")).isEqualTo("character varying");
    }

    /** Проверяем STRING/ORDINAL и составной UNIQUE документа. */
    @Test
    void shouldSupportEnumeratedStringAndOrdinal() {
        assertThat(columnType("it_documents", "state")).isEqualTo("character varying");
        assertThat(columnType("it_documents", "kind"))
                .isIn("smallint", "integer");
        assertThat(constraintExists("uq_it_docs_country_number", "u")).isTrue();
        // Hibernate обычно добавляет CHECK, ограничивающий значения Enum.
        assertThat(constraintCount("it_documents", "c")).isGreaterThanOrEqualTo(1);
        assertThat(foreignKeyCount("it_documents", "it_countries")).isGreaterThanOrEqualTo(1);
    }

    /** Проверяем циклические ссылки — оба FK создаются после создания таблиц. */
    @Test
    void shouldCreateCyclicReferences() {
        assertThat(exists("it_cycle_a")).isTrue();
        assertThat(exists("it_cycle_b")).isTrue();
        assertThat(foreignKeyCount("it_cycle_a", "it_cycle_b")).isGreaterThanOrEqualTo(1);
        assertThat(foreignKeyCount("it_cycle_b", "it_cycle_a")).isGreaterThanOrEqualTo(1);
    }

    /** Повторная регистрация не должна менять время генерации или пользовательские строки. */
    @Test
    void shouldNotRegenerateRegisteredTables() {
        LocalDateTime previous = repository.findByCode("it_countries").orElseThrow().getGeneratedAt();
        Long id = jdbc.queryForObject(
                "insert into it_schema.it_countries(name, code) values (?, ?) returning id",
                Long.class, "Проверочная страна", "ZZZ"
        );
        try {
            registration.registerAll();
            assertThat(repository.findByCode("it_countries").orElseThrow().getGeneratedAt())
                    .isEqualTo(previous);
            assertThat(jdbc.queryForObject(
                    "select count(*) from it_schema.it_countries where id = ?", Integer.class, id
            )).isEqualTo(1);
        } finally {
            jdbc.update("delete from it_schema.it_countries where id = ?", id);
        }
    }

    /** Повторная попытка создания существующей физической таблицы должна завершаться ошибкой. */
    @Test
    void shouldRejectPhysicalTableWithoutRegeneration() {
        DictionaryMetadataDto descriptor = new DictionaryMetadataDto(
                "it_countries", "Повторное создание", ItCountry.class, ItCountryDto.class
        );
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> generator.generateBatch(List.of(descriptor))
        )).isInstanceOf(DictionaryRegistrationException.class)
          .hasMessageContaining("уже существует");
        assertThat(exists("it_countries")).isTrue();
    }

    /** Проверяем общий откат PostgreSQL DDL и строки реестра в одной транзакции. */
    @Test
    void shouldRollBackDdlAndRegistryTogether() {
        DictionaryMetadataDto descriptor = new DictionaryMetadataDto(
                "it_rollback_fixture", "Проверка отката", ItRollbackFixture.class, ItRollbackFixtureDto.class
        );
        var transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> {
                generator.generateBatch(List.of(descriptor));

                CommonDictionary row = new CommonDictionary();
                row.setCode(descriptor.code());
                row.setName(descriptor.name());
                row.setEntityClass(descriptor.entityClass().getSimpleName());
                row.setDtoClass(descriptor.dtoClass().getName());
                row.setGeneratedAt(LocalDateTime.now());
                repository.saveAndFlush(row);

                // Имитируем ошибку после создания таблицы и регистрации.
                status.setRollbackOnly();
            });
        } finally {
            assertThat(exists("it_rollback_fixture")).isFalse();
            assertThat(repository.findByCode("it_rollback_fixture")).isEmpty();
        }
    }

    /** Проверяем одновременные повторные регистрации и advisory lock. */
    @Test
    void shouldAllowConcurrentRepeatRegistrations() {
        CompletableFuture<Void> first = CompletableFuture.runAsync(registration::registerAll);
        CompletableFuture<Void> second = CompletableFuture.runAsync(registration::registerAll);
        try {
            CompletableFuture.allOf(first, second).join();
        } catch (CompletionException e) {
            throw new AssertionError("Параллельная регистрация завершилась ошибкой", e.getCause());
        }
        assertThat(repository.findByCode("it_countries")).isPresent();
    }

    /** Проверяем, что UNIQUE действительно отклоняет повторяющийся код. */
    @Test
    void shouldEnforceCountryUniqueConstraint() {
        jdbc.update("insert into it_schema.it_countries(name, code) values (?, ?)", "Первая страна", "UQ1");
        try {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into it_schema.it_countries(name, code) values (?, ?)",
                    "Вторая страна", "UQ1"
            )).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("delete from it_schema.it_countries where code = ?", "UQ1");
        }
    }

    /** При изменении аннотации название справочника синхронизируется без DDL. */
    @Test
    void shouldSynchronizeMutableRegistryMetadata() {
        LocalDateTime generatedAt = repository.findByCode("it_countries").orElseThrow().getGeneratedAt();
        jdbc.update("update it_schema.common_dictionaries set name = ? where code = ?",
                "Устаревшее название", "it_countries");
        registration.registerAll();
        CommonDictionary row = repository.findByCode("it_countries").orElseThrow();
        assertThat(row.getName()).isEqualTo("Тестовые страны");
        assertThat(row.getGeneratedAt()).isEqualTo(generatedAt);
    }

    /** Существование физической таблицы без регистрации должно блокировать генерацию. */
    @Test
    void shouldRejectOrphanTable() {
        jdbc.execute("create table it_schema.it_rollback_fixture (id bigint primary key)");
        try {
            DictionaryMetadataDto descriptor = new DictionaryMetadataDto(
                    "it_rollback_fixture", "Осиротевшая таблица",
                    ItRollbackFixture.class, ItRollbackFixtureDto.class
            );
            var transaction = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> transaction.executeWithoutResult(
                    status -> generator.generateBatch(List.of(descriptor))
            )).isInstanceOf(DictionaryRegistrationException.class)
              .hasMessageContaining("уже существует");
        } finally {
            // Удаляем только собственную тестовую таблицу во временном контейнере.
            jdbc.execute("drop table if exists it_schema.it_rollback_fixture");
        }
    }

    /** Зарегистрированный справочник без физической таблицы должен вызывать ошибку. */
    @Test
    void shouldFailWhenRegisteredPhysicalTableIsMissing() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            // PostgreSQL откатит переименование вместе с этой транзакцией.
            jdbc.execute("alter table it_schema.it_regions rename to it_regions_hidden");
            registration.registerAll();
        })).isInstanceOf(DictionaryRegistrationException.class)
          .hasMessageContaining("не имеет физической таблицы");
        assertThat(exists("it_regions")).isTrue();
        assertThat(exists("it_regions_hidden")).isFalse();
    }

    /** Изменение кода или имени Entity в реестре требует ручного разрешения конфликта. */
    @Test
    void shouldRejectRegistryEntityRenameConflict() {
        jdbc.update("update it_schema.common_dictionaries set entity_class = ? where code = ?",
                "RenamedItRegion", "it_regions");
        try {
            assertThatThrownBy(registration::registerAll)
                    .isInstanceOf(DictionaryRegistrationException.class)
                    .hasMessageContaining("неизменяемость");
        } finally {
            jdbc.update("update it_schema.common_dictionaries set entity_class = ? where code = ?",
                    "ItRegion", "it_regions");
        }
    }

    // Ниже находятся только проверки реальной схемы PostgreSQL.

    private boolean exists(String objectName) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass(format('%I.%I', ?, ?)) is not null",
                Boolean.class, "it_schema", objectName
        ));
    }

    private boolean columnExists(String table, String column) {
        Boolean result = jdbc.queryForObject("""
                select exists (
                    select 1 from information_schema.columns
                    where table_schema = 'it_schema' and table_name = ? and column_name = ?
                )
                """, Boolean.class, table, column);
        return Boolean.TRUE.equals(result);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_schema = 'it_schema' and table_name = ? and column_name = ?
                """, String.class, table, column);
    }

    private Integer columnLength(String table, String column) {
        return jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_schema = 'it_schema' and table_name = ? and column_name = ?
                """, Integer.class, table, column);
    }

    /**
     * Колонки is_nullable / is_identity фиксированы и задаются только в тесте.
     * Не используйте этот метод для пользовательских SQL-идентификаторов.
     */
    private String columnValue(String table, String column, String field) {
        if (!Set.of("is_nullable", "is_identity").contains(field)) {
            throw new IllegalArgumentException("Недопустимое проверяемое свойство колонки: " + field);
        }
        return jdbc.queryForObject("""
                select %s from information_schema.columns
                where table_schema = 'it_schema' and table_name = ? and column_name = ?
                """.formatted(field), String.class, table, column);
    }

    private Integer constraintCount(String table, String type) {
        return jdbc.queryForObject("""
                select count(*) from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
                where n.nspname = 'it_schema' and t.relname = ? and c.contype = ?
                """, Integer.class, table, type);
    }

    private boolean constraintExists(String name, String type) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from pg_constraint c
                    join pg_namespace n on n.oid = c.connamespace
                    where n.nspname = 'it_schema' and c.conname = ? and c.contype = ?
                )
                """, Boolean.class, name, type));
    }

    private boolean indexExists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from pg_indexes
                    where schemaname = 'it_schema' and indexname = ?
                )
                """, Boolean.class, name));
    }

    private Integer foreignKeyCount(String source, String target) {
        return jdbc.queryForObject("""
                select count(*) from pg_constraint c
                join pg_class source_table on source_table.oid = c.conrelid
                join pg_class target_table on target_table.oid = c.confrelid
                join pg_namespace n on n.oid = source_table.relnamespace
                where n.nspname = 'it_schema' and c.contype = 'f'
                  and source_table.relname = ? and target_table.relname = ?
                """, Integer.class, source, target);
    }
}
