
package com.argent.servicecommon.dictionary.controller;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.metadata.DictionaryMetadataService;
import com.argent.servicecommon.dictionary.service.DictionaryDtoCrudService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;

/**
 * Универсальный REST-контроллер зарегистрированных справочников.
 *
 * <p>Предоставляет единый API для справочников независимо
 * от конкретного типа JPA-сущности и соответствующего DTO.</p>
 *
 * <p>Классы Entity и DTO определяются исключительно через
 * проверенные метаданные зарегистрированного справочника.</p>
 *
 * <p>При создании и обновлении принимается полный DTO.
 * Частичное обновление PATCH не поддерживается.</p>
 */
@RestController
@RequestMapping("/api/argent-dictionary")
public class ArgentDictionaryController {
    private final DictionaryDtoCrudService crudService;
    private final DictionaryMetadataService metadataService;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    /**
     * Создаёт универсальный контроллер справочников.
     */
    public ArgentDictionaryController(
            DictionaryDtoCrudService crudService,
            DictionaryMetadataService metadataService,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate,
            JsonMapper jsonMapper
    ) {
        this.crudService = Objects.requireNonNull(crudService);
        this.metadataService = Objects.requireNonNull(metadataService);
        this.entityManager = Objects.requireNonNull(entityManager);
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.jsonMapper = Objects.requireNonNull(jsonMapper);
    }

    /**
     * Возвращает описание справочника для динамической формы.
     *
     * @param code код справочника
     * @return описание полей и ограничений DTO
     */
    @GetMapping("/{code}/form")
    public Object getForm(@PathVariable String code) {

        // Сервис метаданных извлекает описание полей DTO,
        // аннотации DictionaryField и ограничения валидации.
        return metadataService.describe(code);
    }

    /**
     * Возвращает запись справочника в формате DTO.
     *
     * @param code код справочника
     * @param id идентификатор записи
     * @return типизированный DTO на уровне сервиса
     */
    @GetMapping("/{code}/records/{id}")
    public Object findById(
            @PathVariable String code,
            @PathVariable String id
    ) {
        DictionaryTypes types = resolveTypes(code);

        return findTyped(
                code,
                id,
                types.entityClass(),
                types.dtoClass()
        );
    }

    /**
     * Создаёт запись справочника из полного DTO.
     *
     * @param code код справочника
     * @param body JSON-объект новой записи
     * @return DTO созданной записи
     */
    @PostMapping("/{code}/records")
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(
            @PathVariable String code,
            @RequestBody String body
    ) {
        DictionaryTypes types = resolveTypes(code);

        return createTyped(
                code,
                body,
                types.entityClass(),
                types.dtoClass()
        );
    }

    /**
     * Полностью обновляет существующую запись.
     *
     * <p>Клиент передаёт все необходимые значения DTO.
     * Проверка ограничений выполняется в DTO-сервисе.</p>
     *
     * @param code код справочника
     * @param id идентификатор существующей записи
     * @param body полный JSON-объект DTO
     * @return DTO обновлённой записи
     */
    @PutMapping("/{code}/records/{id}")
    public Object update(
            @PathVariable String code,
            @PathVariable String id,
            @RequestBody String body
    ) {
        DictionaryTypes types = resolveTypes(code);

        return updateTyped(
                code,
                id,
                body,
                types.entityClass(),
                types.dtoClass()
        );
    }

    /**
     * Удаляет запись зарегистрированного справочника.
     *
     * @param code код справочника
     * @param id идентификатор записи
     */
    @DeleteMapping("/{code}/records/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String code,
            @PathVariable String id
    ) {
        // Существование записи и права на изменение
        // должны проверяться на сервисном уровне.
        crudService.delete(code, id);
    }

    /**
     * Определяет пару Entity/DTO по зарегистрированному
     * коду справочника.
     *
     * <p>Не принимает имена классов от HTTP-клиента
     * и не загружает произвольные Java-классы.</p>
     */
    private DictionaryTypes resolveTypes(String code) {

        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "Код справочника не может быть пустым."
            );
        }

        // Ищем регистрацию справочника.
        // Используем только параметризованный SQL.
        List<DictionaryRegistryEntry> entries = jdbcTemplate.query(
                """
                SELECT entity_class, dto_class
                FROM common_dictionaries
                WHERE code = ?
                """,
                (rs, rowNum) -> new DictionaryRegistryEntry(
                        rs.getString("entity_class"),
                        rs.getString("dto_class")
                ),
                code
        );
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "Справочник '%s' не зарегистрирован."
                            .formatted(code)
            );
        }

        if (entries.size() != 1) {
            throw new IllegalStateException(
                    "Для справочника '%s' обнаружено "
                            .formatted(code)
                            + "несколько записей реестра."
            );
        }

        DictionaryRegistryEntry entry = entries.getFirst();

        // Кандидатов берём только из уже известных Hibernate
        // сущностей, а не по произвольному имени класса.
        for (EntityType<?> entityType :
                entityManager.getMetamodel().getEntities()) {

            Class<?> entityClass = entityType.getJavaType();

            if (!entityClass.getSimpleName()
                    .equals(entry.entityClass())) {
                continue;
            }

            Table table = entityClass.getAnnotation(Table.class);

            if (table == null || !code.equals(table.name())) {
                continue;
            }

            ArgentDictionary annotation =
                    entityClass.getAnnotation(ArgentDictionary.class);

            if (annotation == null) {
                continue;
            }

            // Сверяем DTO из реестра с DTO,
            // указанным на самой JPA-сущности.
            Class<?> dtoClass = annotation.dtoClass();

            if (!dtoClass.getName().equals(entry.dtoClass())) {
                throw new IllegalStateException(
                        "Для справочника '%s' DTO в реестре "
                                .formatted(code)
                                + "не соответствует аннотации ArgentDictionary."
                );
            }

            return new DictionaryTypes(entityClass, dtoClass);
        }

        throw new IllegalStateException(
                "Не удалось определить JPA-сущность "
                        + "зарегистрированного справочника '%s'."
                        .formatted(code)
        );
    }

    /**
     * Получает запись через типизированный DTO-сервис.
     */
    private <E, D> D findTyped(
            String code,
            String id,
            Class<E> entityClass,
            Class<D> dtoClass
    ) {
        return crudService.findById(
                code,
                id,
                entityClass,
                dtoClass
        );
    }

    /**
     * Создаёт запись через типизированный DTO-сервис.
     */
    private <E, D> D createTyped(
            String code,
            String body,
            Class<E> entityClass,
            Class<D> dtoClass
    ) {
        D dto = readDto(body, dtoClass);

        return crudService.create(
                code,
                dto,
                entityClass,
                dtoClass
        );
    }

    /**
     * Обновляет запись через типизированный DTO-сервис.
     */
    private <E, D> D updateTyped(
            String code,
            String id,
            String body,
            Class<E> entityClass,
            Class<D> dtoClass
    ) {
        D dto = readDto(body, dtoClass);

        return crudService.update(
                code,
                id,
                dto,
                entityClass,
                dtoClass
        );
    }

    /**
     * Преобразует JSON в зарегистрированный класс DTO.
     *
     * <p>Вложенные DTO обрабатываются Jackson.
     * Преобразование связей в Entity далее выполняется
     * универсальным DictionaryDtoMapper.</p>
     */
    private <D> D readDto(String body, Class<D> dtoClass) {

        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "Тело запроса не может быть пустым."
            );
        }

        try {
            // Jackson использует фактический класс DTO,
            // найденный по зарегистрированному справочнику.
            D dto = jsonMapper.readValue(body, dtoClass);

            if (dto == null) {
                throw new IllegalArgumentException(
                        "Тело запроса должно содержать DTO."
                );
            }

            return dto;

        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Некорректный JSON или структура DTO.",
                    exception
            );
        }
    }

    /**
     * Данные записи реестра справочников.
     */
    private record DictionaryRegistryEntry(
            String entityClass,
            String dtoClass
    ) {
    }

    /**
     * Проверенная пара классов Entity и DTO.
     */
    private record DictionaryTypes(
            Class<?> entityClass,
            Class<?> dtoClass
    ) {
    }
}
