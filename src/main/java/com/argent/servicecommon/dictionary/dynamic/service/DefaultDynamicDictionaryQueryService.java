package com.argent.servicecommon.dictionary.dynamic.service;

import com.argent.servicecommon.dictionary.dynamic.dto.*;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.metadata.RegisteredDictionary;
import com.argent.servicecommon.dictionary.mapper.DictionaryDtoMapper;
import com.argent.servicecommon.dictionary.mapper.DictionaryProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Безопасный постраничный запрос через JPA Criteria API.
 *
 * <p>Сортировка и фильтрация разрешены только по простым полям,
 * объявленным в DTO и известным JPA. Имена SQL-таблиц и полей из запроса
 * никогда не подставляются непосредственно в SQL.</p>
 */
public class DefaultDynamicDictionaryQueryService implements DynamicDictionaryQueryService {
    public static final int MAX_PAGE_SIZE = 100;
    private final DynamicDictionaryCatalog catalog;
    private final DynamicDictionarySchemaService schemaService;
    private final EntityManager entityManager;
    private final DictionaryDtoMapper mapper;

    public DefaultDynamicDictionaryQueryService(DynamicDictionaryCatalog catalog,
                                                DynamicDictionarySchemaService schemaService,
                                                EntityManager entityManager,
                                                DictionaryDtoMapper mapper) {
        this.catalog = Objects.requireNonNull(catalog);
        this.schemaService = Objects.requireNonNull(schemaService);
        this.entityManager = Objects.requireNonNull(entityManager);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    @Transactional(readOnly = true)
    public DynamicPage<?> page(String code, int page, int size, String sort,
                               String search, Map<String, String> filters) {
        validatePage(page, size);
        RegisteredDictionary dictionary = catalog.require(code);
        return query(dictionary, page, size, sort, search, filters == null ? Map.of() : filters);
    }

    @Override
    @Transactional(readOnly = true)
    public DynamicPage<DynamicOption> options(String code, int page, int size, String search) {
        validatePage(page, size);
        RegisteredDictionary dictionary = catalog.require(code);
        DynamicDictionarySchema schema = schemaService.describe(code);
        String sort = schema.displayField() != null && schema.fields().stream().anyMatch(
                field -> field.name().equals(schema.displayField()) && field.visible())
                ? schema.displayField() : schema.idField();
        DynamicPage<?> rows = query(dictionary, page, size, sort + ",asc", search, Map.of());
        List<DynamicOption> options = rows.items().stream().map(dto -> {
            // Внешние ключи на фронте передаются в виде строковых ID.
            Object identifier = DictionaryProperties.read(dto, schema.idField());
            if (identifier == null) {
                throw new IllegalStateException("Для варианта выбора отсутствует идентификатор.");
            }
            Object label = schema.displayField() == null ? null
                    : DictionaryProperties.read(dto, schema.displayField());
            return new DynamicOption(identifier.toString(),
                    label == null ? identifier.toString() : label.toString());
        }).toList();
        return DynamicPage.of(options, page, size, rows.totalElements());
    }

    private <E, D> DynamicPage<D> query(RegisteredDictionary dictionary, int page, int size,
                                        String sort, String search, Map<String, String> filters) {
        @SuppressWarnings("unchecked")
        Class<E> entityClass = (Class<E>) dictionary.entityClass();
        @SuppressWarnings("unchecked")
        Class<D> dtoClass = (Class<D>) dictionary.dtoClass();
        Map<String, Class<?>> fields = permittedFields(entityClass, dtoClass);
        SortSpec ordering = parseSort(sort, fields, entityClass);
        validateSearch(search);
        Map<String, Object> converted = convertFilters(filters, fields);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<E> select = cb.createQuery(entityClass);
        Root<E> root = select.from(entityClass);
        select.select(root);
        select.where(predicates(cb, root, search, converted, fields));
        var primaryOrder = ordering.descending() ? cb.desc(root.get(ordering.field()))
                : cb.asc(root.get(ordering.field()));
        // Вторичная сортировка по ID предотвращает скачки между страницами
        // при одинаковых значениях основного поля сортировки.
        String idField = entityManager.getMetamodel().entity(entityClass).getSingularAttributes()
                .stream().filter(attribute -> attribute.isId()).map(Attribute::getName)
                .findFirst().orElseThrow();
        if (idField.equals(ordering.field())) {
            select.orderBy(primaryOrder);
        } else {
            select.orderBy(primaryOrder, cb.asc(root.get(idField)));
        }
        int offset = Math.toIntExact((long) page * size);
        List<E> entities = entityManager.createQuery(select)
                .setFirstResult(offset).setMaxResults(size).getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<E> countRoot = countQuery.from(entityClass);
        countQuery.select(cb.count(countRoot));
        countQuery.where(predicates(cb, countRoot, search, converted, fields));
        Long total = entityManager.createQuery(countQuery).getSingleResult();

        // Трансформация выполняется внутри транзакции, когда LAZY-связи ещё доступны.
        List<D> dtos = entities.stream().map(entity -> mapper.toDto(entity, dtoClass)).toList();
        return DynamicPage.of(dtos, page, size, total == null ? 0L : total);
    }

    /** Извлекаем только скалярные свойства, присутствующие одновременно в Entity и DTO. */
    private Map<String, Class<?>> permittedFields(Class<?> entityClass, Class<?> dtoClass) {
        ManagedType<?> model = entityManager.getMetamodel().managedType(entityClass);
        Map<String, Class<?>> allowed = new HashMap<>();
        for (var property : DictionaryProperties.dtoProperties(dtoClass)) {
            if (!property.visible()) continue;
            try {
                Attribute<?, ?> attribute = model.getAttribute(property.name());
                if (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC
                        && !attribute.isCollection() && scalar(attribute.getJavaType())) {
                    allowed.put(property.name(), attribute.getJavaType());
                }
            } catch (IllegalArgumentException ignored) {
                // Неизвестные поля DTO никогда не допускаются в запросы к БД.
            }
        }
        return Map.copyOf(allowed);
    }

    private SortSpec parseSort(String sort, Map<String, Class<?>> fields, Class<?> entityClass) {
        var entityType = entityManager.getMetamodel().entity(entityClass);
        if (!entityType.hasSingleIdAttribute()) {
            throw new DynamicDictionaryBadRequestException(
                    "Постраничный список не поддерживает составные первичные ключи.");
        }
        String primary = entityType.getSingularAttributes().stream().filter(a -> a.isId())
                .map(Attribute::getName).findFirst()
                .orElseThrow(() -> new DynamicDictionaryBadRequestException(
                        "Для постраничного списка необходим простой первичный ключ."));
        if (sort == null || sort.isBlank()) return new SortSpec(primary, false);
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !fields.containsKey(parts[0])) {
            throw new DynamicDictionaryBadRequestException(
                    "Сортировка разрешена только по простым полям DTO: " + sort);
        }
        boolean desc = parts.length == 2 && parts[1].equalsIgnoreCase("desc");
        if (parts.length == 2 && !(desc || parts[1].equalsIgnoreCase("asc"))) {
            throw new DynamicDictionaryBadRequestException(
                    "Направление сортировки должно быть asc или desc.");
        }
        return new SortSpec(parts[0], desc);
    }

    private Map<String, Object> convertFilters(Map<String, String> filters,
                                                Map<String, Class<?>> fields) {
        Map<String, Object> result = new HashMap<>();
        for (var filter : filters.entrySet()) {
            Class<?> type = fields.get(filter.getKey());
            if (type == null) {
                throw new DynamicDictionaryBadRequestException(
                        "Фильтрация по полю '%s' недоступна.".formatted(filter.getKey()));
            }
            try {
                result.put(filter.getKey(), parseValue(type, filter.getValue()));
            } catch (RuntimeException exception) {
                throw new DynamicDictionaryBadRequestException(
                        "Некорректное значение фильтра '%s'.".formatted(filter.getKey()), exception);
            }
        }
        return Map.copyOf(result);
    }

    /** Преобразует фильтр в тип значения JPA без SQL-конкатенации. */
    private static Object parseValue(Class<?> type, String raw) {
        if (raw == null) throw new IllegalArgumentException("Отсутствует значение фильтра.");
        if (type == String.class) return raw;
        if (type == UUID.class) return UUID.fromString(raw);
        if (type == Long.class || type == long.class) return Long.valueOf(raw);
        if (type == Integer.class || type == int.class) return Integer.valueOf(raw);
        if (type == Short.class || type == short.class) return Short.valueOf(raw);
        if (type == Byte.class || type == byte.class) return Byte.valueOf(raw);
        if (type == Float.class || type == float.class) return Float.valueOf(raw);
        if (type == Double.class || type == double.class) return Double.valueOf(raw);
        if (type == BigDecimal.class) return new BigDecimal(raw);
        if (type == BigInteger.class) return new BigInteger(raw);
        if (type == Boolean.class || type == boolean.class) {
            if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("Ожидается true или false.");
            return Boolean.valueOf(raw);
        }
        if (type == LocalDate.class) return LocalDate.parse(raw);
        if (type == LocalDateTime.class) return LocalDateTime.parse(raw);
        if (type == LocalTime.class) return LocalTime.parse(raw);
        if (type == ZonedDateTime.class) return ZonedDateTime.parse(raw);
        if (type == Instant.class) return Instant.parse(raw);
        if (type == OffsetDateTime.class) return OffsetDateTime.parse(raw);
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(raw)) return constant;
            }
            throw new IllegalArgumentException("Неизвестное значение перечисления.");
        }
        throw new IllegalArgumentException("Тип фильтра не поддерживается.");
    }

    private static <E> Predicate[] predicates(CriteriaBuilder cb, Root<E> root, String search,
                                               Map<String, Object> filters,
                                               Map<String, Class<?>> fields) {
        List<Predicate> predicates = new ArrayList<>();
        filters.forEach((field, value) -> predicates.add(cb.equal(root.get(field), value)));
        if (search != null && !search.isBlank()) {
            String escaped = search.toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            List<Predicate> alternatives = new ArrayList<>();
            fields.forEach((name, type) -> {
                if (type == String.class) {
                    alternatives.add(cb.like(cb.lower(root.get(name).as(String.class)),
                            "%" + escaped + "%", '\\'));
                }
            });
            if (alternatives.isEmpty()) {
                // Без доступных строковых полей поиск не должен показывать всё.
                predicates.add(cb.disjunction());
            } else {
                predicates.add(cb.or(alternatives.toArray(Predicate[]::new)));
            }
        }
        return predicates.toArray(Predicate[]::new);
    }

    private static boolean scalar(Class<?> type) {
        return type == String.class || type == UUID.class || type.isEnum()
                || type == Boolean.class || type == boolean.class
                || Number.class.isAssignableFrom(type) || type.isPrimitive() && type != char.class
                || type == LocalDate.class || type == LocalDateTime.class || type == LocalTime.class
                || type == ZonedDateTime.class || type == Instant.class
                || type == OffsetDateTime.class;
    }

    private static void validateSearch(String search) {
        if (search != null && search.length() > 200) {
            throw new DynamicDictionaryBadRequestException(
                    "Поисковая строка не должна превышать 200 символов.");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new DynamicDictionaryBadRequestException(
                    "Некорректная пагинация: page >= 0, size от 1 до 100, смещение не более 2147483647.");
        }
    }

    private record SortSpec(String field, boolean descending) { }
}
