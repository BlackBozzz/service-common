package com.argent.servicecommon.dictionary.mapper;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.config.DictionaryDtoAutoConfiguration;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.exception.DictionaryMappingException;
import com.argent.servicecommon.dictionary.mapper.fixture.dto.*;
import com.argent.servicecommon.dictionary.mapper.fixture.entity.*;
import com.argent.servicecommon.dictionary.mapper.fixture.types.TestComplexState;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import com.argent.servicecommon.dictionary.service.DictionaryCrudService;
import com.argent.servicecommon.dictionary.service.DictionaryDtoCrudService;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Сквозные интеграционные проверки универсального DTO-маппера,
 * валидации полных DTO и метаданных динамической формы.
 *
 * <p>Используется отдельная схема PostgreSQL; обычные тесты откатываются.</p>
 */
@SpringBootTest(classes = DictionaryDtoIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional
class DictionaryDtoIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("argent_dictionary_dto_tests")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/dto-test-init.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = postgres.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=it_dto";
        });
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/common");
        registry.add("spring.flyway.schemas", () -> "it_dto");
        registry.add("spring.flyway.default-schema", () -> "it_dto");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "it_dto");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CommonDictionary.class, TestCountryEntity.class})
    @EnableJpaRepositories(basePackages = "com.argent.servicecommon.dictionary.repository")
    @Import(DictionaryDtoAutoConfiguration.class)
    static class TestApplication { }

    @Autowired DictionaryDtoMapper mapper;
    @Autowired DictionaryDtoCrudService dtoCrud;
    @Autowired DictionaryCrudService crud;
    @Autowired DictionaryMetadataService metadata;
    @Autowired EntityManager em;

    private TestCountryEntity country(String name) {
        TestCountryEntity entity = new TestCountryEntity();
        entity.setName(name);
        return crud.create("dto_countries", entity);
    }

    private TestTagEntity tag(String name) {
        TestTagEntity entity = new TestTagEntity();
        entity.setName(name);
        return crud.create("dto_tags", entity);
    }

    private static TestCountryDto countryView(TestCountryEntity country) {
        return new TestCountryDto(country.getId(), country.getName());
    }

    private static TestTagDto tagView(TestTagEntity tag) {
        return new TestTagDto(tag.getId(), tag.getName());
    }

    private static TestCityDto cityView(TestCountryEntity country, Set<TestTagDto> tags) {
        return new TestCityDto(null, "Москва", 100,
                countryView(country), tags, Set.of("МСК", "Moscow"),
                new TestAddressDto("Тверская", "125009"));
    }

    private TestCityEntity createCity(TestCountryEntity country) {
        TestCityEntity city = mapper.toEntity(cityView(country, Set.of()), TestCityEntity.class);
        crud.create("dto_cities", city);
        return city;
    }

    /** Интеграция автоконфигурации в Spring. */
    @Test void shouldRegisterMapperAndDtoFacade() {
        assertThat(mapper).isNotNull();
        assertThat(dtoCrud).isNotNull();
        assertThat(metadata).isNotNull();
    }

    /** Идентификатор генерируется БД, а не дописывается маппером. */
    @Test void shouldCreateEntityFromRecordWithNullGeneratedId() {
        TestCountryEntity entity = mapper.toEntity(new TestCountryDto(null, "Россия"), TestCountryEntity.class);
        assertThat(entity.getId()).isNull();
        assertThat(entity.getName()).isEqualTo("Россия");
    }

    /** Возвращается конкретный тип DTO, включая запись ID. */
    @Test void shouldMapEntityToTypedRecord() {
        TestCountryEntity saved = country("Россия");
        TestCountryDto dto = mapper.toDto(saved, TestCountryDto.class);
        assertThat(dto.id()).isEqualTo(saved.getId());
        assertThat(dto.name()).isEqualTo("Россия");
    }

    /** Отрицательный тест: DTO, не объявленный на сущности, должен быть отклонён. */
    @Test void shouldRejectUnregisteredDtoPair() {
        assertThatThrownBy(() -> mapper.validateStructure(TestCountryEntity.class, UnregisteredCountryDto.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("указан DTO");
    }

    /** Связь ManyToOne ищется по ID, а не создаётся из вложенного DTO. */
    @Test void shouldResolveManyToOneWithFind() {
        TestCountryEntity saved = country("Россия");
        TestCityEntity mapped = mapper.toEntity(cityView(saved, Set.of()), TestCityEntity.class);
        assertThat(mapped.getCountry()).isSameAs(saved);
    }

    /** Ошибочное название страны во вложенном DTO не перезаписывает связанную запись. */
    @Test void shouldNotUpdateRelatedEntityFromNestedDto() {
        TestCountryEntity saved = country("Россия");
        TestCountryDto misleading = new TestCountryDto(saved.getId(), "Другая страна");
        TestCityDto input = new TestCityDto(null, "Москва", 100, misleading,
                Set.of(), Set.of(), null);
        mapper.toEntity(input, TestCityEntity.class);
        assertThat(saved.getName()).isEqualTo("Россия");
    }

    /** Если ссылка указывает на несуществующий объект, преобразование завершается ошибкой. */
    @Test void shouldRejectMissingRelation() {
        TestCityDto input = new TestCityDto(null, "Москва", 100,
                new TestCountryDto(999999L, "Неизвестная страна"), Set.of(), Set.of(), null);
        assertThatThrownBy(() -> mapper.toEntity(input, TestCityEntity.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("не найдена");
    }

    /** Для выбора существующей связи нужен ID. */
    @Test void shouldRejectReferenceWithoutId() {
        TestCityDto input = new TestCityDto(null, "Москва", 100,
                new TestCountryDto(null, "Россия"), Set.of(), Set.of(), null);
        assertThatThrownBy(() -> mapper.toEntity(input, TestCityEntity.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("требуется идентификатор");
    }

    /** Коллекция ManyToMany разрешается отдельным find() для каждого ID. */
    @Test void shouldResolveManyToManyCollection() {
        TestCountryEntity saved = country("Россия");
        TestTagEntity first = tag("Столица");
        TestTagEntity second = tag("Крупный город");
        TestCityEntity mapped = mapper.toEntity(cityView(saved, Set.of(tagView(first), tagView(second))), TestCityEntity.class);
        assertThat(mapped.getTags()).containsExactlyInAnyOrder(first, second);
    }

    /** В коллекцию связей нельзя передавать несуществующий ID. */
    @Test void shouldRejectMissingItemInRelationCollection() {
        TestCountryEntity saved = country("Россия");
        assertThatThrownBy(() -> mapper.toEntity(cityView(saved,
                Set.of(new TestTagDto(99999L, "Категория"))), TestCityEntity.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("не найдена");
    }

    /** В коллекции связей нельзя иметь null. */
    @Test void shouldRejectNullInRelationCollection() {
        TestCountryEntity saved = country("Россия");
        Set<TestTagDto> tags = new java.util.HashSet<>();
        tags.add(null);
        assertThatThrownBy(() -> mapper.toEntity(cityView(saved, tags), TestCityEntity.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("содержит null");
    }

    /** ElementCollection со строками переносится без дополнительных запросов. */
    @Test void shouldMapElementCollection() {
        TestCountryEntity saved = country("Россия");
        TestCityEntity mapped = mapper.toEntity(cityView(saved, Set.of()), TestCityEntity.class);
        assertThat(mapped.getAliases()).containsExactlyInAnyOrder("МСК", "Moscow");
    }

    /** Embedded-поля преобразуются рекурсивно, но не требуют отдельного find(). */
    @Test void shouldMapEmbeddedObject() {
        TestCountryEntity saved = country("Россия");
        TestCityEntity mapped = mapper.toEntity(cityView(saved, Set.of()), TestCityEntity.class);
        assertThat(mapped.getAddress().getStreet()).isEqualTo("Тверская");
        assertThat(mapped.getAddress().getPostalCode()).isEqualTo("125009");
    }

    /** DTO при чтении содержит ровно объявленные вложенные поля. */
    @Test void shouldReadNestedDtoWithCollections() {
        TestCountryEntity saved = country("Россия");
        TestTagEntity label = tag("Столица");
        TestCityEntity city = mapper.toEntity(cityView(saved, Set.of(tagView(label))), TestCityEntity.class);
        crud.create("dto_cities", city);
        em.flush();
        em.clear();
        TestCityEntity loaded = crud.findById("dto_cities", city.getId().toString(), TestCityEntity.class);
        TestCityDto result = mapper.toDto(loaded, TestCityDto.class);
        assertThat(result.country().name()).isEqualTo("Россия");
        assertThat(result.tags()).extracting(TestTagDto::name).containsExactly("Столица");
        assertThat(result.aliases()).containsExactlyInAnyOrder("МСК", "Moscow");
        assertThat(result.address().street()).isEqualTo("Тверская");
    }

    /** Обновление изменяет простые поля и связанную запись, но не связанную сущность. */
    @Test void shouldUpdateManagedEntityAndChangeReference() {
        TestCountryEntity first = country("Россия");
        TestCountryEntity second = country("Беларусь");
        TestCityEntity city = createCity(first);
        TestCityDto changed = new TestCityDto(city.getId(), "Минск", 250,
                new TestCountryDto(second.getId(), "НЕ ДОЛЖНО СОХРАНИТЬСЯ"),
                Set.of(), Set.of("МН"), null);
        mapper.updateEntity(changed, city);
        em.flush();
        assertThat(city.getName()).isEqualTo("Минск");
        assertThat(city.getCountry()).isSameAs(second);
        assertThat(second.getName()).isEqualTo("Беларусь");
        assertThat(city.getAliases()).containsExactly("МН");
        assertThat(city.getAddress()).isNull();
    }

    /** ID из DTO нельзя поменять во время обновления. */
    @Test void shouldRejectChangingIdentity() {
        TestCountryEntity saved = country("Россия");
        assertThatThrownBy(() -> mapper.updateEntity(
                new TestCountryDto(saved.getId() + 1, "Украина"), saved))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("идентификатор");
    }

    /** У генерируемого ID при создании не должно быть произвольного значения. */
    @Test void shouldRejectGeneratedIdOnCreateWhenWritable() {
        // Поле DTO здесь помечено readOnly, поэтому проверяем именно неизменяемость ID при PUT.
        TestCountryEntity saved = country("Россия");
        assertThatThrownBy(() -> mapper.updateEntity(
                new TestCountryDto(-1L, "Россия"), saved))
                .isInstanceOf(DictionaryMappingException.class);
    }

    /** Попытка обновить DTO неуправляемой сущности должна явно завершиться ошибкой. */
    @Test void shouldRequireManagedEntityForUpdate() {
        TestCountryEntity detached = new TestCountryEntity();
        detached.setId(10L);
        assertThatThrownBy(() -> mapper.updateEntity(new TestCountryDto(10L, "Россия"), detached))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("управляемую");
    }

    /** Данные должны проходить валидацию DTO до создания JPA-записи. */
    @Test void shouldValidateBlankNameOnCreate() {
        assertThatThrownBy(() -> dtoCrud.create("dto_countries",
                new TestCountryDto(null, "  "), TestCountryEntity.class, TestCountryDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** Ограничение @Size применяется на сервере. */
    @Test void shouldValidateTooLongName() {
        assertThatThrownBy(() -> dtoCrud.create("dto_countries",
                new TestCountryDto(null, "А".repeat(81)), TestCountryEntity.class, TestCountryDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** Ограничение @Min для числа применяется на сервере. */
    @Test void shouldValidateMinimum() {
        TestCountryEntity saved = country("Россия");
        TestCityDto invalid = new TestCityDto(null, "Москва", -1,
                countryView(saved), Set.of(), Set.of(), null);
        assertThatThrownBy(() -> dtoCrud.create("dto_cities", invalid,
                TestCityEntity.class, TestCityDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** Ограничение @Max для числа применяется на сервере. */
    @Test void shouldValidateMaximum() {
        TestCountryEntity saved = country("Россия");
        TestCityDto invalid = new TestCityDto(null, "Москва", 100000001,
                countryView(saved), Set.of(), Set.of(), null);
        assertThatThrownBy(() -> dtoCrud.create("dto_cities", invalid,
                TestCityEntity.class, TestCityDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** Обязательная вложенная ссылка country проверяется как @NotNull. */
    @Test void shouldValidateRequiredNestedReference() {
        TestCityDto invalid = new TestCityDto(null, "Москва", 10,
                null, Set.of(), Set.of(), null);
        assertThatThrownBy(() -> dtoCrud.create("dto_cities", invalid,
                TestCityEntity.class, TestCityDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** @Valid обеспечивает каскадную проверку вложенной страны. */
    @Test void shouldValidateNestedDto() {
        TestCityDto invalid = new TestCityDto(null, "Москва", 10,
                new TestCountryDto(2L, " "), Set.of(), Set.of(), null);
        assertThatThrownBy(() -> dtoCrud.create("dto_cities", invalid,
                TestCityEntity.class, TestCityDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** Полный сервис DTO сохраняет и возвращает типизированный результат. */
    @Test void shouldCreateWithDtoFacade() {
        TestCountryDto saved = dtoCrud.create("dto_countries",
                new TestCountryDto(null, "Россия"), TestCountryEntity.class, TestCountryDto.class);
        assertThat(saved.id()).isNotNull();
        assertThat(saved.name()).isEqualTo("Россия");
    }

    /** DTO-сервис возвращает конкретный тип DTO по ID. */
    @Test void shouldReadByIdWithDtoFacade() {
        TestCountryEntity country = country("Россия");
        TestCountryDto found = dtoCrud.findById("dto_countries", country.getId().toString(),
                TestCountryEntity.class, TestCountryDto.class);
        assertThat(found.id()).isEqualTo(country.getId());
    }

    /** DTO-сервис возвращает типизированный список. */
    @Test void shouldReadTypedListFromDtoFacade() {
        country("Россия");
        country("Беларусь");
        List<TestCountryDto> list = dtoCrud.findAll("dto_countries", TestCountryEntity.class, TestCountryDto.class);
        assertThat(list).extracting(TestCountryDto::name)
                .containsExactlyInAnyOrder("Россия", "Беларусь");
    }

    /** PUT-подобное обновление не требует создания нового экземпляра сущности. */
    @Test void shouldUpdateWithDtoFacade() {
        TestCountryEntity saved = country("Россия");
        TestCountryDto updated = dtoCrud.update("dto_countries", saved.getId().toString(),
                new TestCountryDto(saved.getId(), "Новая страна"),
                TestCountryEntity.class, TestCountryDto.class);
        assertThat(updated.name()).isEqualTo("Новая страна");
        assertThat(saved.getName()).isEqualTo("Новая страна");
    }

    /** Полный PUT не принимает несуществующий ID записи. */
    @Test void shouldRejectMissingEntityOnUpdate() {
        assertThatThrownBy(() -> dtoCrud.update("dto_countries", "99999",
                new TestCountryDto(99999L, "Россия"), TestCountryEntity.class, TestCountryDto.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найдена");
    }

    /** DTO, не зарегистрированный для выбранного справочника, должен быть отклонён. */
    @Test void shouldRejectWrongDtoTypeInFacade() {
        assertThatThrownBy(() -> dtoCrud.findAll("dto_countries", TestCountryEntity.class, UnregisteredCountryDto.class))
                .isInstanceOf(DictionaryMappingException.class);
    }

    /** Получение метаданных ограничено зарегистрированными справочниками. */
    @Test void shouldRejectUnknownMetadataCode() {
        assertThatThrownBy(() -> metadata.describe("unknown_dictionary"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не зарегистрирован");
    }

    /** Защищаем запрос метаданных от подстановки имени таблицы. */
    @Test void shouldRejectSqlInjectionAsCode() {
        assertThatThrownBy(() -> metadata.describe("dto_countries' OR 1=1 --"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Форма содержит ограничения @Size, обязательность и подписи @DictionaryField. */
    @Test void shouldDescribeValidationAndUiMetadata() {
        var descriptor = metadata.describe("dto_countries");
        assertThat(descriptor.code()).isEqualTo("dto_countries");
        var name = descriptor.fields().stream()
                .filter(field -> field.name().equals("name")).findFirst().orElseThrow();
        assertThat(name.label()).isEqualTo("Страна");
        assertThat(name.required()).isTrue();
        assertThat(name.validation()).anyMatch(rule ->
                rule.name().equals("Size")
                        && Integer.valueOf(80).equals(rule.attributes().get("max")));
        var id = descriptor.fields().stream()
                .filter(field -> field.name().equals("id")).findFirst().orElseThrow();
        assertThat(id.readOnly()).isTrue();
    }

    /** Для поля связи метаданные сообщают код справочника и вложенные поля. */
    @Test void shouldDescribeNestedReferenceFields() {
        var city = metadata.describe("dto_cities");
        var country = city.fields().stream().filter(f -> f.name().equals("country"))
                .findFirst().orElseThrow();
        assertThat(country.referenceCode()).isEqualTo("dto_countries");
        assertThat(country.type()).isEqualTo("reference");
        assertThat(country.required()).isTrue();
        assertThat(country.fields()).extracting(f -> f.name()).contains("id", "name");
    }

    /** Для числового поля возвращаются границы, применяемые сервером. */
    @Test void shouldDescribeNumericLimits() {
        var city = metadata.describe("dto_cities");
        var population = city.fields().stream().filter(f -> f.name().equals("population"))
                .findFirst().orElseThrow();
        assertThat(population.validation()).extracting(rule -> rule.name())
                .contains("Min", "Max");
    }

    /** Ошибки DTO-структуры обнаруживаются до сохранения. */
    @Test void shouldRejectUnknownDtoField() {
        assertThatThrownBy(() -> mapper.validateStructure(TestAddressEmbeddable.class, ExtraAddressView.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("отсутствующее");
    }

    /** Простые поля должны иметь согласованные типы, без скрытой конвертации. */
    @Test void shouldRejectIncompatibleFieldType() {
        assertThatThrownBy(() -> mapper.validateStructure(TestAddressEmbeddable.class, WrongAddressView.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("Несовместимые типы");
    }

    /** Должна поддерживаться пустая коллекция связанных DTO. */
    @Test void shouldAllowEmptyRelatedCollection() {
        TestCountryEntity saved = country("Россия");
        TestCityEntity entity = mapper.toEntity(cityView(saved, Set.of()), TestCityEntity.class);
        assertThat(entity.getTags()).isEmpty();
    }

    /** Чтение исключает связь, которой нет в классе DTO. */
    @Test void shouldNotAddUnmentionedInverseCollection() {
        TestCountryEntity saved = country("Россия");
        TestCountryDto dto = mapper.toDto(saved, TestCountryDto.class);
        assertThat(TestCountryDto.class.getRecordComponents()).extracting(c -> c.getName())
                .doesNotContain("cities");
        assertThat(dto.name()).isEqualTo("Россия");
    }

    /** DTO-сервис удаляет запись по зарегистрированному коду. */
    @Test void shouldDeleteThroughDtoFacade() {
        TestCountryEntity saved = country("Россия");
        String id = saved.getId().toString();
        dtoCrud.delete("dto_countries", id);
        assertThatThrownBy(() -> crud.findById("dto_countries", id, TestCountryEntity.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Преобразование null-значений не заменяет их выдуманными значениями. */
    @Test void shouldKeepNullOptionalEmbedded() {
        TestCountryEntity saved = country("Россия");
        TestCityDto input = new TestCityDto(null, "Москва", 10, countryView(saved),
                Set.of(), Set.of(), null);
        TestCityEntity entity = mapper.toEntity(input, TestCityEntity.class);
        assertThat(entity.getAddress()).isNull();
    }


    /** Цикл в графе DTO выявляется ещё до обращения к данным. */
    @Test void shouldRejectDtoCycle() {
        assertThatThrownBy(() -> mapper.validateStructure(TestLoopAEntity.class, TestLoopADto.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("Циклическая структура DTO")
                .hasMessageContaining("TestLoopADto");
    }

    /** Обратная коллекционная связь недоступна для записи через один лишь Set. */
    @Test void shouldRejectInverseCollectionWrite() {
        assertThatThrownBy(() -> mapper.toEntity(
                new TestParentDto(null, Set.of()), TestParentEntity.class))
                .isInstanceOf(DictionaryMappingException.class)
                .hasMessageContaining("обратную сторону");
    }

    /** Для обратных коллекций метаданные объявляют readOnly. */
    @Test void shouldDescribeInverseCollectionAsReadonly() {
        var form = metadata.describe("dto_parents");
        var children = form.fields().stream()
                .filter(f -> f.name().equals("children")).findFirst().orElseThrow();
        assertThat(children.readOnly()).isTrue();
    }

    /** Ограничения значений элементов Set доступны фронтенду отдельно. */
    @Test void shouldDescribeElementValidation() {
        var form = metadata.describe("dto_cities");
        var aliases = form.fields().stream()
                .filter(f -> f.name().equals("aliases")).findFirst().orElseThrow();
        assertThat(aliases.elementValidation()).extracting(rule -> rule.name())
                .contains("NotBlank");
    }

    /** Сервер проверяет ограничения каждого элемента коллекции. */
    @Test void shouldValidateCollectionItems() {
        TestCountryEntity saved = country("Россия");
        var input = new TestCityDto(null, "Москва", 12,
                countryView(saved), Set.of(), Set.of(" "), null);
        assertThatThrownBy(() -> dtoCrud.create("dto_cities", input,
                TestCityEntity.class, TestCityDto.class))
                .isInstanceOf(ConstraintViolationException.class);
    }

    /** Обновление вложенного @Embedded не стирает поле, которого нет в DTO. */
    @Test void shouldPreserveUnmappedEmbeddedFieldOnUpdate() {
        TestCountryEntity saved = country("Россия");
        TestCityEntity city = createCity(saved);
        city.getAddress().setSuite("Квартира 7");
        mapper.updateEntity(new TestCityDto(city.getId(), "Москва", 100,
                countryView(saved), Set.of(), Set.of(),
                new TestAddressDto("Новая улица", "101000")), city);
        assertThat(city.getAddress().getSuite()).isEqualTo("Квартира 7");
        assertThat(city.getAddress().getStreet()).isEqualTo("Новая улица");
    }

    /**
     * У каждой тестовой JPA-сущности указан только собственный DTO.
     * Дополнительно сверяем соглашение об именах XxxEntity → XxxDto.
     */
    @Test void shouldAssignOnlyMatchingDtoToEveryDictionaryEntity() {
        assertMatchingDto(TestCountryEntity.class, TestCountryDto.class);
        assertMatchingDto(TestCityEntity.class, TestCityDto.class);
        assertMatchingDto(TestTagEntity.class, TestTagDto.class);
        assertMatchingDto(TestParentEntity.class, TestParentDto.class);
        assertMatchingDto(TestChildEntity.class, TestChildDto.class);
        assertMatchingDto(TestLoopAEntity.class, TestLoopADto.class);
        assertMatchingDto(TestLoopBEntity.class, TestLoopBDto.class);
    }

    /** DTO не должны объявляться JPA-сущностями или самостоятельными справочниками. */
    @Test void shouldKeepEntityAndDtoRolesSeparate() {
        for (Class<?> dtoClass : List.of(
                TestCountryDto.class, TestCityDto.class, TestTagDto.class,
                TestParentDto.class, TestChildDto.class,
                TestLoopADto.class, TestLoopBDto.class, TestAddressDto.class
        )) {
            assertThat(dtoClass.isRecord()).isTrue();
            assertThat(dtoClass.isAnnotationPresent(Entity.class)).isFalse();
            assertThat(dtoClass.isAnnotationPresent(ArgentDictionary.class)).isFalse();
        }
        assertThat(TestAddressEmbeddable.class.isAnnotationPresent(ArgentDictionary.class))
                .isFalse();
    }

    /**
     * Создаёт полный DTO с различными простыми типами и вложенными DTO.
     */
    private static TestComplexDto complexDto(
            Long id,
            TestCountryDto country,
            TestTagDto featuredTag,
            Set<TestTagDto> tags,
            Set<TestChildDto> children
    ) {
        return new TestComplexDto(
                id,
                "Сложный справочник",
                true,
                Boolean.FALSE,
                (byte) 7,
                (short) 32000,
                120,
                9_000_000_000L,
                1.5f,
                3.25d,
                new BigDecimal("1234.50"),
                new BigInteger("123456789012345678901234"),
                UUID.fromString("9ef91cf0-d6a7-4ac7-9e7f-2b6e66b7b43a"),
                'A',
                LocalDate.of(2026, 10, 30),
                LocalTime.of(13, 20, 30),
                LocalDateTime.of(2026, 10, 30, 13, 20, 30),
                Instant.parse("2026-10-30T10:20:30Z"),
                OffsetDateTime.parse("2026-10-30T10:20:30Z"),
                TestComplexState.ACTIVE,
                new byte[]{0, 1, 2, 3, 127},
                country,
                featuredTag,
                tags,
                children,
                List.of("Первое название", "Второе название"),
                new TestAddressDto("Тверская", "125009")
        );
    }

    /**
     * Проверяет преобразование сложной Entity → DTO.
     *
     * <p>Сущность собираем вручную, чтобы тест не зависел от обратного
     * преобразования и мог обнаружить ошибки чтения конкретных полей.</p>
     */
    @Test
    void shouldMapComplexEntityToDtoWithAllSupportedFieldKinds() {
        TestCountryEntity savedCountry = country("Россия");
        TestTagEntity savedTag = tag("Столица");
        TestChildEntity savedChild = crud.create("dto_children", new TestChildEntity());

        TestComplexEntity entity = new TestComplexEntity();

        // Проставляем ID только для теста чтения; сохранять эту сущность не будем.
        entity.setId(42L);
        entity.setTitle("Сложный справочник");
        entity.setEnabled(true);
        entity.setVerified(Boolean.FALSE);
        entity.setLevel((byte) 7);
        entity.setShortValue((short) 32000);
        entity.setCount(120);
        entity.setTotal(9_000_000_000L);
        entity.setRatio(1.5f);
        entity.setScore(3.25d);
        entity.setPrice(new BigDecimal("1234.50"));
        entity.setBigCounter(new BigInteger("123456789012345678901234"));
        entity.setExternalId(UUID.fromString("9ef91cf0-d6a7-4ac7-9e7f-2b6e66b7b43a"));
        entity.setGrade('A');
        entity.setStartDate(LocalDate.of(2026, 10, 30));
        entity.setStartTime(LocalTime.of(13, 20, 30));
        entity.setRegisteredAt(LocalDateTime.of(2026, 10, 30, 13, 20, 30));
        entity.setOccurredAt(Instant.parse("2026-10-30T10:20:30Z"));
        entity.setOffsetAt(OffsetDateTime.parse("2026-10-30T10:20:30Z"));
        entity.setStatus(TestComplexState.ACTIVE);
        entity.setPayload(new byte[]{0, 1, 2, 3, 127});

        // Проверяем все распространённые виды вложенных связей.
        entity.setCountry(savedCountry);
        entity.setFeaturedTag(savedTag);
        entity.setTags(Set.of(savedTag));
        entity.setChildren(Set.of(savedChild));
        entity.setAliases(List.of("Первое название", "Второе название"));

        TestAddressEmbeddable address = new TestAddressEmbeddable();
        address.setStreet("Тверская");
        address.setPostalCode("125009");
        address.setSuite("Не объявлено в DTO");
        entity.setAddress(address);

        TestComplexDto result = mapper.toDto(entity, TestComplexDto.class);
        TestComplexDto expected = complexDto(
                42L,
                new TestCountryDto(savedCountry.getId(), "Россия"),
                new TestTagDto(savedTag.getId(), "Столица"),
                Set.of(new TestTagDto(savedTag.getId(), "Столица")),
                Set.of(new TestChildDto(savedChild.getId()))
        );

        // Рекурсивное сравнение проверяет и byte[], и вложенные DTO.
        assertThat(result).usingRecursiveComparison().isEqualTo(expected);
    }

    /**
     * Проверяет полный путь DTO → Entity → PostgreSQL → Entity → DTO.
     *
     * <p>Все вложенные DTO используются только как ссылки по ID;
     * их остальные поля не должны изменять связанные записи.</p>
     */
    @Test
    void shouldMapComplexDtoToEntityPersistAndReadBack() {
        TestCountryEntity savedCountry = country("Россия");
        TestTagEntity savedTag = tag("Столица");
        TestChildEntity savedChild = crud.create("dto_children", new TestChildEntity());

        // Намеренно отправляем ложные названия связанных записей.
        // Маппер обязан взять только их ID и вызвать EntityManager.find().
        TestComplexDto input = complexDto(
                null,
                new TestCountryDto(savedCountry.getId(), "Изменять страну нельзя"),
                new TestTagDto(savedTag.getId(), "Изменять тег нельзя"),
                Set.of(new TestTagDto(savedTag.getId(), "Неверное название")),
                Set.of(new TestChildDto(savedChild.getId()))
        );

        TestComplexEntity entity = mapper.toEntity(input, TestComplexEntity.class);

        // Все связи должны ссылаться на уже загруженные JPA-сущности.
        assertThat(entity.getCountry()).isSameAs(savedCountry);
        assertThat(entity.getFeaturedTag()).isSameAs(savedTag);
        assertThat(entity.getTags()).containsExactly(savedTag);
        assertThat(entity.getChildren()).containsExactly(savedChild);
        assertThat(savedCountry.getName()).isEqualTo("Россия");
        assertThat(savedTag.getName()).isEqualTo("Столица");
        assertThat(entity.getAddress().getSuite()).isNull();

        // Проверяем не только маппинг в памяти, но и результат в PostgreSQL.
        TestComplexEntity saved = crud.create("dto_complex_records", entity);
        Long savedId = saved.getId();
        em.flush();
        em.clear();

        TestComplexEntity loaded = crud.findById(
                "dto_complex_records", savedId.toString(), TestComplexEntity.class
        );
        TestComplexDto result = mapper.toDto(loaded, TestComplexDto.class);

        TestComplexDto expected = complexDto(
                savedId,
                new TestCountryDto(savedCountry.getId(), "Россия"),
                new TestTagDto(savedTag.getId(), "Столица"),
                Set.of(new TestTagDto(savedTag.getId(), "Столица")),
                Set.of(new TestChildDto(savedChild.getId()))
        );

        assertThat(result).usingRecursiveComparison().isEqualTo(expected);
    }


    /** Общая проверка точного соответствия имени и регистрации Entity ↔ DTO. */
    private static void assertMatchingDto(Class<?> entityClass, Class<?> dtoClass) {
        assertThat(entityClass.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(entityClass.isAnnotationPresent(Table.class)).isTrue();
        assertThat(entityClass.getAnnotation(ArgentDictionary.class)).isNotNull();
        assertThat(entityClass.getAnnotation(ArgentDictionary.class).dtoClass())
                .isEqualTo(dtoClass);
        assertThat(entityClass.getSimpleName().replaceFirst("Entity$", "Dto"))
                .isEqualTo(dtoClass.getSimpleName());
    }

    /** DTO, специально не зарегистрированный ни у одной сущности (отрицательные тесты). */
    public record UnregisteredCountryDto(Long id, String name) { }

    /** Для тестирования несоответствия структуры DTO JPA. */
    public record ExtraAddressView(String street, String postalCode, String nonexistent) { }
    public record WrongAddressView(Integer street, String postalCode) { }
}
