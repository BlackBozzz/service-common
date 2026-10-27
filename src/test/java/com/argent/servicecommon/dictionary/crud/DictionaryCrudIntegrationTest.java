package com.argent.servicecommon.dictionary.crud;

import com.argent.servicecommon.dictionary.crud.crudfixture.*;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.dictionary.service.DictionaryCrudService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Интеграционные проверки типизированного DictionaryCrudService на реальном PostgreSQL.
 *
 * <p>Временный PostgreSQL из Testcontainers не затрагивает основную базу данных.
 * Каждый обычный тест выполняется в транзакции, которая откатывается после проверки.
 * Два теста с NOT_SUPPORTED отдельно проверяют границы и откат транзакций.</p>
 *
 * <p>Тестовые сущности находятся вне пакета старых тестовых Entity,
 * поэтому набор не меняет количество справочников в DictionarySchemaIntegrationTest.</p>
 */
@SpringBootTest(
        classes = DictionaryCrudIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Testcontainers
@Transactional
class DictionaryCrudIntegrationTest {

    /** Контейнер автоматически уничтожается Testcontainers после завершения тестов. */
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("argent_dictionary_crud_tests")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/crud-test-init.sql");

    /** Подключаем и Hibernate, и Flyway к одной схеме. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String jdbcUrl = postgres.getJdbcUrl();
            return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=it_crud";
        });
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/common");
        registry.add("spring.flyway.schemas", () -> "it_crud");
        registry.add("spring.flyway.default-schema", () -> "it_crud");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "it_crud");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.jpa.properties.hibernate.type.prefer_native_enum_types", () -> "false");
    }

    /**
     * В тестовый persistence unit попадают только библиотечный реестр
     * и специально выделенные сущности для проверки CRUD.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CommonDictionary.class, CrudCountry.class})
    @EnableJpaRepositories(basePackageClasses = CommonDictionaryRepository.class)
    static class TestApplication {
    }

    @Autowired private DictionaryCrudService service;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CommonDictionaryRepository registryRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Проверяем, что автоконфигурация создала сервис и зарегистрировала справочники. */
    @Test
    void shouldRegisterAllCrudFixtures() {
        assertThat(service).isNotNull();
        Set<String> codes = registryRepository.findAll().stream()
                .map(CommonDictionary::getCode)
                .collect(Collectors.toSet());
        assertThat(codes).containsExactlyInAnyOrder(
                "crud_countries", "crud_cities", "crud_categories", "crud_regions",
                "crud_sequences", "crud_integers", "crud_shorts", "crud_bytes",
                "crud_big_integers", "crud_big_decimals");
        assertThat(codes).doesNotContain("crud_unregistered", "crud_composite");
    }

    /** Пустой справочник возвращает пустой типизированный список. */
    @Test
    void shouldReturnEmptyTypedList() {
        List<CrudCountry> countries = service.findAll("crud_countries", CrudCountry.class);
        assertThat(countries).isEmpty();
    }

    /** Список сохраняет конкретный тип и предоставляет его геттеры. */
    @Test
    void shouldReturnTypedListWithSeveralItems() {
        service.create("crud_countries", country("AAA", "Первая страна"));
        service.create("crud_countries", country("BBB", "Вторая страна"));

        List<CrudCountry> countries = service.findAll("crud_countries", CrudCountry.class);
        assertThat(countries).extracting(CrudCountry::getName)
                .containsExactlyInAnyOrder("Первая страна", "Вторая страна");
    }

    /** Возвращаемый тип findById — CrudCountry, а не Object. */
    @Test
    void shouldFindIdentityEntityWithTypedResult() {
        CrudCountry saved = service.create("crud_countries", country("AAA", "Страна"));

        CrudCountry found = service.findById(
                "crud_countries", saved.getId().toString(), CrudCountry.class);
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getName()).isEqualTo("Страна");
    }

    /** UUID-ключ преобразуется из строки и передаётся в EntityManager. */
    @Test
    void shouldFindUuidEntity() {
        CrudCategory category = service.create("crud_categories", category("Категория"));

        CrudCategory found = service.findById(
                "crud_categories", category.getId().toString(), CrudCategory.class);
        assertThat(found.getId()).isEqualTo(category.getId());
        assertThat(found.getName()).isEqualTo("Категория");
    }

    /** Поддерживается строковый идентификатор без GeneratedValue. */
    @Test
    void shouldFindAssignedStringEntity() {
        CrudRegion saved = service.create("crud_regions", region("EU-NORTH", "Север"));
        CrudRegion found = service.findById("crud_regions", "EU-NORTH", CrudRegion.class);
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getName()).isEqualTo("Север");
    }

    /** Проверяем числовые ключи Integer, Short, Byte, BigInteger и BigDecimal. */
    @Test
    void shouldConvertAllSupportedNumericIdTypes() {
        CrudInteger integer = new CrudInteger();
        integer.setId(41);
        integer.setName("Целое число");
        service.create("crud_integers", integer);

        CrudShort shortKey = new CrudShort();
        shortKey.setId((short) 42);
        shortKey.setName("Короткое число");
        service.create("crud_shorts", shortKey);

        CrudByte byteKey = new CrudByte();
        byteKey.setId((byte) 43);
        byteKey.setName("Байтовое число");
        service.create("crud_bytes", byteKey);

        CrudBigInteger bigInteger = new CrudBigInteger();
        bigInteger.setId(new BigInteger("4400000000000000"));
        bigInteger.setName("Большое целое число");
        service.create("crud_big_integers", bigInteger);

        CrudBigDecimal bigDecimal = new CrudBigDecimal();
        bigDecimal.setId(new BigDecimal("45"));
        bigDecimal.setName("Десятичное число");
        service.create("crud_big_decimals", bigDecimal);

        entityManager.flush();
        entityManager.clear();

        assertThat(service.findById("crud_integers", "41", CrudInteger.class).getName())
                .isEqualTo("Целое число");
        assertThat(service.findById("crud_shorts", "42", CrudShort.class).getName())
                .isEqualTo("Короткое число");
        assertThat(service.findById("crud_bytes", "43", CrudByte.class).getName())
                .isEqualTo("Байтовое число");
        assertThat(service.findById("crud_big_integers", "4400000000000000", CrudBigInteger.class).getName())
                .isEqualTo("Большое целое число");
        assertThat(service.findById("crud_big_decimals", "45", CrudBigDecimal.class).getName())
                .isEqualTo("Десятичное число");
    }

    /** SEQUENCE-идентификатор генерируется Hibernate и доступен после сохранения. */
    @Test
    void shouldCreateAndReadSequenceEntity() {
        CrudSequence item = new CrudSequence();
        item.setName("Документ с последовательностью");
        CrudSequence created = service.create("crud_sequences", item);

        assertThat(created.getId()).isNotNull();
        CrudSequence found = service.findById(
                "crud_sequences", created.getId().toString(), CrudSequence.class);
        assertThat(found.getName()).isEqualTo(item.getName());
    }

    /** Отсутствующая запись не превращается в null. */
    @Test
    void shouldRejectMissingRecordOnRead() {
        assertThatThrownBy(() -> service.findById(
                "crud_countries", "9999999", CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");
    }

    /** Некорректный UUID должен приводить к понятной ошибке. */
    @Test
    void shouldRejectMalformedUuid() {
        assertThatThrownBy(() -> service.findById(
                "crud_categories", "не-uuid", CrudCategory.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректный идентификатор");
    }

    /** Некорректное число не доходит до запроса к БД. */
    @Test
    void shouldRejectMalformedLongId() {
        assertThatThrownBy(() -> service.findById(
                "crud_countries", "abc", CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректный идентификатор");
    }

    /** Переполнение при преобразовании в Byte обнаруживается до выполнения SQL. */
    @Test
    void shouldRejectNumericOverflow() {
        assertThatThrownBy(() -> service.findById(
                "crud_bytes", "1000", CrudByte.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректный идентификатор");
    }

    /** Пустой или отсутствующий идентификатор не допускается. */
    @Test
    void shouldRejectEmptyIdentifiers() {
        assertThatThrownBy(() -> service.findById(
                "crud_countries", null, CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Идентификатор");
        assertThatThrownBy(() -> service.findById(
                "crud_countries", "  ", CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Идентификатор");
    }

    /** Запрос неизвестного кода отклоняется. */
    @Test
    void shouldRejectUnknownDictionaryCode() {
        assertThatThrownBy(() -> service.findAll("unknown_dictionary", CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не зарегистрирован");
    }

    /** Код справочника не может быть null или пустой строкой. */
    @Test
    void shouldRejectEmptyDictionaryCode() {
        assertThatThrownBy(() -> service.findAll(null, CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Код справочника");
        assertThatThrownBy(() -> service.findAll(" ", CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Код справочника");
    }

    /** Код никогда не включается в SQL как исполняемый фрагмент. */
    @Test
    void shouldNotInterpretDictionaryCodeAsSql() {
        assertThatThrownBy(() -> service.findAll(
                "crud_countries' OR 1=1 --", CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не зарегистрирован");
    }

    /** Class<T> является обязательной частью типобезопасного API чтения. */
    @Test
    void shouldRejectNullRequestedEntityClass() {
        assertThatThrownBy(() -> service.findAll("crud_countries", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("класс");
    }

    /** Нельзя прочитать страны с помощью CrudCategory.class. */
    @Test
    void shouldRejectWrongRequestedClassOnFindById() {
        assertThatThrownBy(() -> service.findById(
                "crud_countries", "1", CrudCategory.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("но запрошена сущность");
    }

    /** Аналогичная проверка действует при чтении коллекции. */
    @Test
    void shouldRejectWrongRequestedClassOnFindAll() {
        assertThatThrownBy(() -> service.findAll(
                "crud_countries", CrudCategory.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("но запрошена сущность");
    }

    /** Нельзя получить неаннотированную сущность даже при наличии её в JPA Metamodel. */
    @Test
    void shouldRejectUnregisteredEntity() {
        assertThatThrownBy(() -> service.findAll(
                "crud_unregistered", CrudUnregistered.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не зарегистрирован");
    }

    /** Создание автоматически присваивает идентификатор IDENTITY и сохраняет тип. */
    @Test
    void shouldCreateCountryWithIdentity() {
        CrudCountry created = service.create("crud_countries", country("AAA", "Новая страна"));
        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("Новая страна");

        entityManager.flush();
        entityManager.clear();
        assertThat(service.findById("crud_countries", created.getId().toString(), CrudCountry.class)
                .getName()).isEqualTo("Новая страна");
    }

    /** Для UUID генерацию выполняет Hibernate. */
    @Test
    void shouldCreateCategoryWithUuid() {
        CrudCategory created = service.create("crud_categories", category("Новая категория"));
        assertThat(created.getId()).isNotNull();
        assertThat(created.getId()).isInstanceOf(UUID.class);
    }

    /** У назначаемого строкового ключа значение не должно быть потеряно. */
    @Test
    void shouldCreateWithAssignedStringId() {
        CrudRegion created = service.create("crud_regions", region("RU-CENTER", "Центр"));
        assertThat(created.getId()).isEqualTo("RU-CENTER");
    }

    /** Нельзя создать null-сущность. */
    @Test
    void shouldRejectNullEntityOnCreate() {
        assertThatThrownBy(() -> service.create("crud_countries", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Создаваемая сущность");
    }

    /** Нельзя выдать сущность одного типа за другой справочник. */
    @Test
    void shouldRejectWrongEntityClassOnCreate() {
        assertThatThrownBy(() -> service.create("crud_countries", category("Чужая сущность")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ожидается тип");
    }

    /** Обычная JPA-сущность без ArgentDictionary не может сохраняться через сервис. */
    @Test
    void shouldRejectUnregisteredEntityOnCreate() {
        CrudUnregistered value = new CrudUnregistered();
        value.setId(5L);
        assertThatThrownBy(() -> service.create("crud_unregistered", value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не зарегистрирован");
    }

    /** Проверяем фактический NOT NULL на уровне PostgreSQL. */
    @Test
    void shouldRejectNullRequiredDatabaseColumn() {
        CrudCountry invalid = country("AAA", null);
        assertThatThrownBy(() -> {
            service.create("crud_countries", invalid);
            entityManager.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    /** Проверяем реальный UNIQUE, а не только Java-валидацию. */
    @Test
    void shouldRejectDuplicateUniqueCode() {
        service.create("crud_countries", country("AAA", "Первая"));
        entityManager.flush();

        assertThatThrownBy(() -> {
            service.create("crud_countries", country("AAA", "Вторая"));
            entityManager.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    /** Обновляем отсоединённую сущность, а затем проверяем значения из БД. */
    @Test
    void shouldUpdateDetachedEntity() {
        CrudCountry created = service.create("crud_countries", country("AAA", "До изменения"));
        Long id = created.getId();
        entityManager.flush();
        entityManager.clear();

        CrudCountry detached = country("AAA", "После изменения");
        detached.setId(id);
        CrudCountry updated = service.update("crud_countries", id.toString(), detached);

        assertThat(updated.getId()).isEqualTo(id);
        assertThat(updated.getName()).isEqualTo("После изменения");

        entityManager.flush();
        entityManager.clear();
        assertThat(service.findById("crud_countries", id.toString(), CrudCountry.class).getName())
                .isEqualTo("После изменения");
    }

    /** merge принимает также управляемую сущность. */
    @Test
    void shouldUpdateManagedEntity() {
        CrudCountry country = service.create("crud_countries", country("AAA", "До"));
        country.setName("После");

        CrudCountry updated = service.update(
                "crud_countries", country.getId().toString(), country);
        assertThat(updated.getName()).isEqualTo("После");
    }

    /** Не допускаем вызов обновления с null. */
    @Test
    void shouldRejectNullEntityOnUpdate() {
        assertThatThrownBy(() -> service.update("crud_countries", "1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Обновляемая сущность");
    }

    /** Обновление записи запрещено при несовпадении класса и реестра. */
    @Test
    void shouldRejectWrongEntityClassOnUpdate() {
        assertThatThrownBy(() -> service.update(
                "crud_countries", "1", category("Чужая")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ожидается тип");
    }

    /** Идентификатор запроса и идентификатор сущности должны совпадать. */
    @Test
    void shouldRejectMismatchedIdOnUpdate() {
        CrudCountry created = service.create("crud_countries", country("AAA", "Исходная"));

        assertThatThrownBy(() -> service.update(
                "crud_countries", Long.toString(created.getId() + 1), created))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не совпадает");
    }

    /** merge не должен превращать обновление отсутствующей записи в создание. */
    @Test
    void shouldRejectMissingEntityOnUpdate() {
        CrudCountry absent = country("AAA", "Несуществующая");
        absent.setId(9_999_999L);

        assertThatThrownBy(() -> service.update(
                "crud_countries", "9999999", absent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");
    }

    /** Проверка ID срабатывает и в операции update. */
    @Test
    void shouldRejectMalformedIdOnUpdate() {
        CrudCountry value = country("AAA", "Страна");
        value.setId(1L);
        assertThatThrownBy(() -> service.update("crud_countries", "ошибка", value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректный идентификатор");
    }

    /** Удаляем запись по строковому представлению Long ID. */
    @Test
    void shouldDeleteExistingCountry() {
        CrudCountry country = service.create("crud_countries", country("AAA", "Для удаления"));
        String id = country.getId().toString();

        service.delete("crud_countries", id);
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> service.findById("crud_countries", id, CrudCountry.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");
    }

    /** Проверяем удаление сущности с UUID-ключом. */
    @Test
    void shouldDeleteUuidEntity() {
        CrudCategory category = service.create("crud_categories", category("Удаляемая"));
        String id = category.getId().toString();
        service.delete("crud_categories", id);
        entityManager.flush();
        entityManager.clear();

        assertThat(service.findAll("crud_categories", CrudCategory.class)).isEmpty();
    }

    /** Удаление несуществующей записи не должно молча проходить. */
    @Test
    void shouldRejectMissingEntityOnDelete() {
        assertThatThrownBy(() -> service.delete("crud_countries", "9999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");
    }

    /** В операциях удаления действует та же проверка формата ID. */
    @Test
    void shouldRejectMalformedIdOnDelete() {
        assertThatThrownBy(() -> service.delete("crud_countries", "123abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректный идентификатор");
    }

    /** Нельзя удалить запись из неизвестного справочника. */
    @Test
    void shouldRejectUnknownDictionaryOnDelete() {
        assertThatThrownBy(() -> service.delete("unknown_dictionary", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не зарегистрирован");
    }

    /** Связь ManyToOne должна корректно сохраняться и читаться. */
    @Test
    void shouldCreateAndReadEntityWithManyToOne() {
        CrudCountry country = service.create("crud_countries", country("AAA", "Страна"));
        CrudCity city = new CrudCity();
        city.setName("Город");
        city.setCountry(country);
        CrudCity created = service.create("crud_cities", city);

        entityManager.flush();
        entityManager.clear();
        CrudCity found = service.findById("crud_cities", created.getId().toString(), CrudCity.class);
        assertThat(found.getName()).isEqualTo("Город");
        assertThat(found.getCountry().getId()).isEqualTo(country.getId());
    }

    /** База не должна разрешать удалять родителя при существующей обязательной FK-ссылке. */
    @Test
    void shouldRespectForeignKeyConstraintOnDelete() {
        CrudCountry country = service.create("crud_countries", country("AAA", "Родитель"));
        CrudCity city = new CrudCity();
        city.setName("Дочерняя запись");
        city.setCountry(country);
        service.create("crud_cities", city);
        entityManager.flush();

        assertThatThrownBy(() -> {
            service.delete("crud_countries", country.getId().toString());
            entityManager.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    /** Не допускаем разрешения сущности при подменённых метаданных реестра. */
    @Test
    void shouldRejectRegistryMetadataMismatch() {
        jdbc.update(
                "update common_dictionaries set entity_class = ? where code = ?",
                "OtherEntity", "crud_countries");

        assertThatThrownBy(() -> service.findAll("crud_countries", CrudCountry.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JPA-сущность");
    }

    /** Для составного ключа выводим предусмотренную ошибку до обращения к несуществующей таблице. */
    @Test
    void shouldRejectCompositeIdentifier() {
        // Сущность присутствует в Hibernate, но не помечена ArgentDictionary.
        // Ручная запись в тестовый реестр нужна только для проверки ветки convertId().
        jdbc.update("""
                insert into common_dictionaries(code, name, entity_class, dto_class, generated_at)
                values (?, ?, ?, ?, current_timestamp)
                """, "crud_composite", "Составной тестовый ключ", "CrudComposite",
                CrudDto.class.getName());

        assertThatThrownBy(() -> service.findById(
                "crud_composite", "1:AA", CrudComposite.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Составные идентификаторы");
    }

    /** Неподдерживаемый простой тип ID должен отклоняться явно. */
    @Test
    void shouldRejectUnsupportedIdentifierType() {
        // Сущность известна Hibernate, но генератор справочников её не регистрирует.
        // Добавляем временную запись реестра внутри откатываемой транзакции.
        jdbc.update("""
                insert into common_dictionaries(code, name, entity_class, dto_class, generated_at)
                values (?, ?, ?, ?, current_timestamp)
                """, "crud_unsupported_id", "Неподдерживаемый ID", "CrudUnsupportedId",
                CrudDto.class.getName());

        assertThatThrownBy(() -> service.findById(
                "crud_unsupported_id", "A", CrudUnsupportedId.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неподдерживаемый тип идентификатора");
    }

    /** Удаление записи с назначенным строковым идентификатором. */
    @Test
    void shouldDeleteAssignedStringId() {
        service.create("crud_regions", region("DELETE-ME", "Участок"));
        service.delete("crud_regions", "DELETE-ME");
        entityManager.flush();
        entityManager.clear();

        assertThat(service.findAll("crud_regions", CrudRegion.class)).isEmpty();
    }

    /** Обновление записи со строковым идентификатором. */
    @Test
    void shouldUpdateAssignedStringEntity() {
        service.create("crud_regions", region("REG-1", "Прежнее имя"));
        entityManager.flush();
        entityManager.clear();

        CrudRegion updated = service.update(
                "crud_regions", "REG-1", region("REG-1", "Новое имя"));
        assertThat(updated.getName()).isEqualTo("Новое имя");
    }

    /** Чужая JPA-сущность не должна читаться даже через существующий код. */
    @Test
    void shouldRejectWrongClassForUnregisteredJpaEntity() {
        assertThatThrownBy(() -> service.findAll(
                "crud_countries", CrudUnregistered.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("но запрошена сущность");
    }

    /** Код другого существующего справочника не позволяет сохранить неправильную сущность. */
    @Test
    void shouldRejectCreateWithAnotherRegisteredCode() {
        assertThatThrownBy(() -> service.create(
                "crud_categories", country("AAA", "Не категория")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ожидается тип");
    }

    /** Изменение без ID не должно интерпретироваться как создание новой строки. */
    @Test
    void shouldRejectUpdateWithoutEntityId() {
        CrudCountry saved = service.create("crud_countries", country("AAA", "Исходная"));
        CrudCountry withoutId = country("AAA", "Новая");

        assertThatThrownBy(() -> service.update(
                "crud_countries", saved.getId().toString(), withoutId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не совпадает");
    }

    /** Отсутствующий ID проверяется и в операции удаления. */
    @Test
    void shouldRejectNullIdOnDelete() {
        assertThatThrownBy(() -> service.delete("crud_countries", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Идентификатор");
    }

    /** Сервис не принимает null-код на создание. */
    @Test
    void shouldRejectNullCodeOnCreate() {
        assertThatThrownBy(() -> service.create(null, country("AAA", "Страна")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Код справочника");
    }

    /** Удаление также ограничено зарегистрированными кодами справочников. */
    @Test
    void shouldRejectNullCodeOnDelete() {
        assertThatThrownBy(() -> service.delete(null, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Код справочника");
    }

    /** Проверяем, что транзакция действительно откатывает сохранение сущности. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackCrudChangesAfterException() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> {
            service.create("crud_countries", country("RB1", "Для отката"));
            throw new IllegalStateException("Проверка отката транзакции");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Проверка отката");

        Integer count = jdbc.queryForObject(
                "select count(*) from it_crud.crud_countries where code = ?",
                Integer.class, "RB1");
        assertThat(count).isZero();
    }

    /** Проверяем самостоятельное открытие и завершение транзакций сервисом. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldCommitCreateAndDeleteWithoutOuterTransaction() {
        CrudCountry created = service.create("crud_countries", country("CM1", "Запись"));
        Long id = created.getId();

        Integer countAfterCreate = jdbc.queryForObject(
                "select count(*) from it_crud.crud_countries where id = ?",
                Integer.class, id);
        assertThat(countAfterCreate).isEqualTo(1);

        // Удаление вызвано без тестовой транзакции: сервис должен открыть свою.
        service.delete("crud_countries", id.toString());
        Integer countAfterDelete = jdbc.queryForObject(
                "select count(*) from it_crud.crud_countries where id = ?",
                Integer.class, id);
        assertThat(countAfterDelete).isZero();
    }

    /** Создаёт корректную тестовую страну с обязательными полями. */
    private static CrudCountry country(String code, String name) {
        CrudCountry entity = new CrudCountry();
        entity.setCode(code);
        entity.setName(name);
        return entity;
    }

    /** Создаёт корректную тестовую категорию с UUID-идентификатором. */
    private static CrudCategory category(String name) {
        CrudCategory entity = new CrudCategory();
        entity.setName(name);
        return entity;
    }

    /** Создаёт регион со строковым идентификатором. */
    private static CrudRegion region(String id, String name) {
        CrudRegion entity = new CrudRegion();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }
}
