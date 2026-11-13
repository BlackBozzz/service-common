package com.argent.servicecommon.dictionary.mapper;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.annotation.ArgentDataTable;
import com.argent.servicecommon.dictionary.exception.DictionaryMappingException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Один универсальный DTO-маппер на базе метамодели JPA.
 *
 * <p>Преобразует только поля, объявленные в DTO. Для связи при записи
 * использует только идентификатор вложенного DTO, выполняет find() и
 * не создаёт/изменяет связанную запись.</p>
 *
 * <p>Запись полной DTO-модели: null переносится как null. Ключи JPA
 * защищены от изменения. Для обратных коллекционных связей запись
 * запрещена — изменение только этой стороны не сохраняет FK.</p>
 */
public class DefaultDictionaryDtoMapper implements DictionaryDtoMapper {
    private final EntityManager entityManager;

    public DefaultDictionaryDtoMapper(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager не может быть null.");
    }

    @Override
    public <E, D> D toDto(E entity, Class<D> dtoClass) {
        Objects.requireNonNull(entity, "Сущность не может быть null.");
        Objects.requireNonNull(dtoClass, "Класс DTO не может быть null.");
        Class<?> entityClass = managedClass(entity);
        validateStructure(entityClass, dtoClass);
        return readObject(entity, entityClass, dtoClass);
    }

    @Override
    public <E, D> E toEntity(D dto, Class<E> entityClass) {
        Objects.requireNonNull(dto, "DTO не может быть null.");
        Objects.requireNonNull(entityClass, "Класс сущности не может быть null.");
        validateStructure(entityClass, dto.getClass());
        E entity = DictionaryProperties.instantiate(entityClass);
        writeObject(dto, entity, entityClass, false);
        return entity;
    }

    @Override
    public <E, D> void updateEntity(D dto, E entity) {
        Objects.requireNonNull(dto, "DTO не может быть null.");
        Objects.requireNonNull(entity, "Сущность не может быть null.");
        Class<?> entityClass = managedClass(entity);
        validateStructure(entityClass, dto.getClass());
        writeObject(dto, entity, entityClass, true);
    }

    @Override
    public void validateStructure(Class<?> entityClass, Class<?> dtoClass) {
        Objects.requireNonNull(entityClass, "Класс сущности не может быть null.");
        Objects.requireNonNull(dtoClass, "Класс DTO не может быть null.");
        validateGraph(entityClass, dtoClass, new ArrayDeque<>());
    }

    /** Проверяет DTO-граф ДО обхода данных, чтобы циклы выявлялись даже при null. */
    private void validateGraph(Class<?> entityClass, Class<?> dtoClass, Deque<Class<?>> path) {
        if (path.contains(dtoClass)) {
            String chain = path.stream().map(Class::getSimpleName)
                    .collect(Collectors.joining(" → ")) + " → " + dtoClass.getSimpleName();
            throw new DictionaryMappingException("Циклическая структура DTO: " + chain);
        }
        verifyAnnotatedDto(entityClass, dtoClass);
        path.addLast(dtoClass);
        try {
            ManagedType<?> model = managedType(entityClass);
            for (var field : DictionaryProperties.dtoProperties(dtoClass)) {
                Attribute<?, ?> attribute = requireAttribute(model, field.name());
                var associationKind = attribute.getPersistentAttributeType();
                boolean collection = attribute.isCollection();
                if (collection != Collection.class.isAssignableFrom(field.type())) {
                    throw new DictionaryMappingException(
                            "Поле '%s' имеет несовместимую форму коллекции в DTO."
                                    .formatted(field.name()));
                }
                Class<?> valueType = collection ? DictionaryProperties.elementClass(field) : field.type();
                Class<?> targetType = collection
                        ? ((PluralAttribute<?, ?, ?>) attribute).getElementType().getJavaType()
                        : attribute.getJavaType();
                if (isAssociation(associationKind) || associationKind == Attribute.PersistentAttributeType.EMBEDDED
                        || (associationKind == Attribute.PersistentAttributeType.ELEMENT_COLLECTION
                        && !simpleType(targetType))) {
                    if (simpleType(valueType)) {
                        throw new DictionaryMappingException(
                                "Поле '%s' должно содержать вложенный DTO, а не простой тип."
                                        .formatted(field.name()));
                    }
                    validateGraph(targetType, valueType, path);
                } else if (!compatible(targetType, valueType)) {
                    throw new DictionaryMappingException(
                            "Несовместимые типы поля '%s': Entity=%s, DTO=%s."
                                    .formatted(field.name(), targetType.getSimpleName(),
                                            valueType.getSimpleName()));
                }
            }
        } finally {
            path.removeLast();
        }
    }

    /** Преобразует объявленные поля в record или DTO с обычными полями. */
    private <D> D readObject(Object entity, Class<?> entityClass, Class<D> dtoClass) {
        ManagedType<?> model = managedType(entityClass);
        Map<String, Object> values = new LinkedHashMap<>();
        for (var field : DictionaryProperties.dtoProperties(dtoClass)) {
            Attribute<?, ?> attribute = requireAttribute(model, field.name());
            Object original = DictionaryProperties.read(entity, field.name());
            Object mapped = readValue(original, attribute, field);
            values.put(field.name(), mapped);
        }
        return DictionaryProperties.newDto(dtoClass, values);
    }

    /** Преобразует значения Entity → DTO, включая вложенные DTO и коллекции. */
    private Object readValue(Object original, Attribute<?, ?> attribute,
                             DictionaryProperties.Property field) {
        if (original == null) return null;
        var kind = attribute.getPersistentAttributeType();
        if (attribute.isCollection()) {
            Class<?> elementDto = DictionaryProperties.elementClass(field);
            Class<?> elementEntity = ((PluralAttribute<?, ?, ?>) attribute).getElementType().getJavaType();
            Collection<?> source = (Collection<?>) original;
            List<Object> transformed = new ArrayList<>(source.size());
            for (Object item : source) {
                transformed.add(item == null ? null :
                        (isAssociation(kind) || (kind == Attribute.PersistentAttributeType.ELEMENT_COLLECTION
                                && !simpleType(elementEntity)))
                                ? readObject(item, elementEntity, elementDto) : item);
            }
            return collectionFor(field.type(), transformed);
        }
        if (isAssociation(kind) || kind == Attribute.PersistentAttributeType.EMBEDDED) {
            return readObject(original, attribute.getJavaType(), field.type());
        }
        return original;
    }

    /** Полностью переносит поля DTO в сущность без изменения связанных записей. */
    private void writeObject(Object dto, Object entity, Class<?> entityClass, boolean updating) {
        ManagedType<?> model = managedType(entityClass);
        for (var field : DictionaryProperties.dtoProperties(dto.getClass())) {
            Attribute<?, ?> attribute = requireAttribute(model, field.name());
            Object value = DictionaryProperties.read(dto, field.name());
            if (attribute instanceof SingularAttribute<?, ?> singular && singular.isId()) {
                // ID обновлять нельзя. При создании допускается явно назначенный ID,
                // но ключ, генерируемый БД, должен приходить без значения.
                Object existingId = DictionaryProperties.read(entity, field.name());
                if (updating) {
                    if (value != null && !Objects.equals(value, existingId)) {
                        throw new DictionaryMappingException(
                                "Нельзя менять идентификатор сущности через DTO.");
                    }
                } else if (!field.readOnly()) {
                    if (DictionaryProperties.hasGeneratedId(entityClass, field.name())) {
                        if (value != null) {
                            throw new DictionaryMappingException(
                                    "Генерируемый идентификатор должен быть null при создании.");
                        }
                    } else {
                        DictionaryProperties.write(entity, field.name(), value);
                    }
                }
                continue;
            }
            if (field.readOnly()) continue;
            if (isAssociation(attribute.getPersistentAttributeType())
                    && DictionaryProperties.inverseAssociation(entityClass, field.name())) {
                throw new DictionaryMappingException(
                        "Нельзя записывать обратную сторону JPA-связи '%s'; "
                                .formatted(field.name())
                                + "управляйте связью на стороне владельца или объявите поле readOnly.");
            }
            if (updating && entityManager.contains(entity) == false) {
                throw new DictionaryMappingException(
                        "Обновление требует управляемую JPA-сущность внутри транзакции.");
            }
            Object mapped = writeValue(value, attribute, field,
                    updating ? DictionaryProperties.read(entity, field.name()) : null);
            DictionaryProperties.write(entity, field.name(), mapped);
        }
    }

    /** При записи вложенной связи ищет существующую запись только по её ID. */
    private Object writeValue(Object value, Attribute<?, ?> attribute,
                              DictionaryProperties.Property field, Object previousValue) {
        if (value == null) return null;
        var kind = attribute.getPersistentAttributeType();
        if (attribute.isCollection()) {
            Class<?> elementEntity = ((PluralAttribute<?, ?, ?>) attribute).getElementType().getJavaType();
            Collection<?> source = (Collection<?>) value;
            List<Object> converted = new ArrayList<>(source.size());
            for (Object item : source) {
                if (item == null) {
                    throw new DictionaryMappingException(
                            "Коллекция связи '%s' содержит null.".formatted(field.name()));
                }
                if (isAssociation(kind)) {
                    converted.add(requireReference(elementEntity, item));
                } else if (kind == Attribute.PersistentAttributeType.ELEMENT_COLLECTION
                        && !simpleType(elementEntity)) {
                    Object embedded = DictionaryProperties.instantiate(elementEntity);
                    writeObject(item, embedded, elementEntity, false);
                    converted.add(embedded);
                } else {
                    converted.add(item);
                }
            }
            return collectionFor(attribute.getJavaType(), converted);
        }
        if (isAssociation(kind)) return requireReference(attribute.getJavaType(), value);
        if (kind == Attribute.PersistentAttributeType.EMBEDDED) {
            // Встраиваемый объект — часть текущей записи, а не отдельный справочник.
            // Создаём новый объект по полному DTO: никаких скрытых дополнений полей.
            Object embedded = previousValue != null ? previousValue
                    : DictionaryProperties.instantiate(attribute.getJavaType());
            writeObject(value, embedded, attribute.getJavaType(), false);
            return embedded;
        }
        return value;
    }

    /** Возвращает связанную управляемую сущность, не изменяя её значения. */
    private Object requireReference(Class<?> relatedClass, Object nestedDto) {
        verifyAnnotatedDto(relatedClass, nestedDto.getClass());
        EntityType<?> type = entityManager.getMetamodel().entity(relatedClass);
        String keyName = type.getSingularAttributes().stream()
                .filter(SingularAttribute::isId)
                .map(SingularAttribute::getName)
                .findFirst()
                .orElseThrow(() -> new DictionaryMappingException(
                        "Составной или неизвестный идентификатор связанной сущности не поддерживается."));
        Object id = DictionaryProperties.read(nestedDto, keyName);
        if (id == null) {
            throw new DictionaryMappingException(
                    "Для связи с '%s' требуется идентификатор '%s'."
                            .formatted(relatedClass.getSimpleName(), keyName));
        }
        Object found = entityManager.find(relatedClass, id);
        if (found == null) {
            throw new DictionaryMappingException(
                    "Связанная запись '%s' с идентификатором '%s' не найдена."
                            .formatted(relatedClass.getSimpleName(), id));
        }
        return found;
    }

    private void verifyAnnotatedDto(Class<?> entityClass, Class<?> dtoClass) {
        ArgentDictionary dictionary = entityClass.getAnnotation(ArgentDictionary.class);
        ArgentDataTable dataTable = entityClass.getAnnotation(ArgentDataTable.class);
        if ((dictionary != null && !dictionary.dtoClass().equals(dtoClass))
                || (dataTable != null && !dataTable.dtoClass().equals(dtoClass))) {
            throw new DictionaryMappingException(
                    "Для сущности '%s' указан DTO '%s', но ожидается '%s'."
                            .formatted(entityClass.getSimpleName(), dtoClass.getName(),
                                    dictionary.dtoClass().getName()));
        }
    }

    private Class<?> managedClass(Object entity) {
        return entityManager.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .filter(type -> type.isInstance(entity))
                .findFirst()
                .orElseThrow(() -> new DictionaryMappingException(
                        "Передан объект, не являющийся зарегистрированной JPA-сущностью."));
    }

    private ManagedType<?> managedType(Class<?> entityClass) {
        try { return entityManager.getMetamodel().managedType(entityClass); }
        catch (IllegalArgumentException exception) {
            throw new DictionaryMappingException(
                    "Тип '%s' отсутствует в JPA Metamodel."
                            .formatted(entityClass.getName()), exception);
        }
    }

    private Attribute<?, ?> requireAttribute(ManagedType<?> model, String name) {
        try { return model.getAttribute(name); }
        catch (IllegalArgumentException exception) {
            throw new DictionaryMappingException(
                    "DTO содержит поле '%s', отсутствующее в JPA-сущности '%s'."
                            .formatted(name, model.getJavaType().getSimpleName()), exception);
        }
    }

    private static boolean isAssociation(Attribute.PersistentAttributeType type) {
        return type == Attribute.PersistentAttributeType.MANY_TO_ONE
                || type == Attribute.PersistentAttributeType.ONE_TO_ONE
                || type == Attribute.PersistentAttributeType.MANY_TO_MANY
                || type == Attribute.PersistentAttributeType.ONE_TO_MANY;
    }

    private static boolean simpleType(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class
                || Number.class.isAssignableFrom(type) || type == Boolean.class
                || type == Character.class || type == java.util.UUID.class
                || java.time.temporal.TemporalAccessor.class.isAssignableFrom(type)
                || type == java.util.Date.class || type == byte[].class;
    }

    private static boolean compatible(Class<?> entityType, Class<?> dtoType) {
        return boxed(entityType).equals(boxed(dtoType));
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object collectionFor(Class<?> targetType, List<Object> elements) {
        if (Set.class.isAssignableFrom(targetType)) return new LinkedHashSet<>(elements);
        if (Collection.class.isAssignableFrom(targetType)) return new ArrayList<>(elements);
        throw new DictionaryMappingException(
                "Поддерживаются только интерфейсы List, Set и Collection для коллекций DTO.");
    }
}
