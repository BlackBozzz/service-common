package com.argent.servicecommon.dictionary.metadata;

import com.argent.servicecommon.dictionary.annotation.ArgentDictionary;
import com.argent.servicecommon.dictionary.annotation.ArgentDataTable;
import com.argent.servicecommon.dictionary.exception.DictionaryMappingException;
import com.argent.servicecommon.dictionary.mapper.DictionaryDtoMapper;
import com.argent.servicecommon.dictionary.mapper.DictionaryProperties;
import com.argent.servicecommon.dictionary.record.DictionaryDefinition;
import com.argent.servicecommon.dictionary.record.DictionaryFieldDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.validation.Validator;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;

/**
 * Извлекает метаданные зарегистрированных справочников для динамических форм.
 *
 * <p>Источники: реестр common_dictionaries, @ArgentDictionary,
 * @DictionaryField, Bean Validation и метамодель JPA.</p>
 */
public class DefaultDictionaryMetadataService implements DictionaryMetadataService {
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final DictionaryDtoMapper mapper;

    public DefaultDictionaryMetadataService(EntityManager entityManager, JdbcTemplate jdbc,
                                            Validator validator, DictionaryDtoMapper mapper) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.validator = Objects.requireNonNull(validator);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public DictionaryDefinition describe(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Код справочника не может быть пустым.");
        }
        // Имя таблицы никогда не вставляется в SQL из HTTP-запроса.
        List<RegistryEntry> records = jdbc.query(
                "select code, name, entity_class, dto_class from common_dictionaries where code = ?",
                (rs, index) -> new RegistryEntry(rs.getString("code"), rs.getString("name"),
                        rs.getString("entity_class"), rs.getString("dto_class")), code);
        if (records.isEmpty()) {
            // Пользовательские таблицы публикуются только из уже зарегистрированных JPA-сущностей.
            // Никакие имена классов из HTTP-запросов не загружаем.
            for (var type : entityManager.getMetamodel().getEntities()) {
                Class<?> entityClass = type.getJavaType();
                Table table = entityClass.getAnnotation(Table.class);
                ArgentDataTable dataTable = entityClass.getAnnotation(ArgentDataTable.class);
                if (table != null && code.equals(table.name()) && dataTable != null) {
                    mapper.validateStructure(entityClass, dataTable.dtoClass());
                    return new DictionaryDefinition(code, dataTable.name(), entityClass.getName(),
                            dataTable.dtoClass().getName(), describeFields(entityClass, dataTable.dtoClass()));
                }
            }
            throw new IllegalArgumentException("Таблица или справочник '%s' не зарегистрированы.".formatted(code));
        }
        RegistryEntry row = records.getFirst();
        for (var type : entityManager.getMetamodel().getEntities()) {
            Class<?> entityClass = type.getJavaType();
            Table table = entityClass.getAnnotation(Table.class);
            if (!entityClass.getSimpleName().equals(row.entityClass)
                    || table == null || !table.name().equals(row.code)) continue;
            ArgentDictionary definition = entityClass.getAnnotation(ArgentDictionary.class);
            if (definition == null || !definition.dtoClass().getName().equals(row.dtoClass)) {
                throw new DictionaryMappingException(
                        "Метаданные DTO справочника '%s' не совпадают с реестром."
                                .formatted(code));
            }
            Class<?> dtoClass = definition.dtoClass();
            mapper.validateStructure(entityClass, dtoClass);
            return new DictionaryDefinition(code, row.name, entityClass.getName(),
                    dtoClass.getName(), describeFields(entityClass, dtoClass));
        }
        throw new DictionaryMappingException(
                "Для справочника '%s' не обнаружена соответствующая JPA-сущность."
                        .formatted(code));
    }

    private List<DictionaryFieldDefinition> describeFields(Class<?> entityClass, Class<?> dtoClass) {
        ManagedType<?> model = entityManager.getMetamodel().managedType(entityClass);
        List<DictionaryFieldDefinition> output = new ArrayList<>();
        for (var field : DictionaryProperties.dtoProperties(dtoClass)) {
            Attribute<?, ?> attr = model.getAttribute(field.name());
            boolean collection = attr.isCollection();
            Class<?> targetType = collection
                    ? ((PluralAttribute<?, ?, ?>) attr).getElementType().getJavaType()
                    : attr.getJavaType();
            Class<?> valueType = collection ? DictionaryProperties.elementClass(field) : field.type();
            boolean association = switch (attr.getPersistentAttributeType()) {
                case MANY_TO_ONE, ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY -> true;
                default -> false;
            };
            boolean embedded = attr.getPersistentAttributeType() == Attribute.PersistentAttributeType.EMBEDDED
                    || (attr.getPersistentAttributeType() == Attribute.PersistentAttributeType.ELEMENT_COLLECTION
                    && !simple(targetType));
            String referenceCode = null;
            if (association) {
                ArgentDictionary dictionary = targetType.getAnnotation(ArgentDictionary.class);
                if (dictionary != null) {
                    Table table = targetType.getAnnotation(Table.class);
                    if (table != null) referenceCode = table.name();
                }
            }
            var propertyDescriptor = validator.getConstraintsForClass(dtoClass)
                    .getConstraintsForProperty(field.name());
            Set<ConstraintDescriptor<?>> descriptors = propertyDescriptor == null
                    ? Set.of() : propertyDescriptor.getConstraintDescriptors();
            boolean required = descriptors.stream().anyMatch(descriptor -> {
                String simpleName = descriptor.getAnnotation().annotationType().getSimpleName();
                return simpleName.equals("NotNull") || simpleName.equals("NotBlank")
                        || simpleName.equals("NotEmpty");
            });
            List<DictionaryFieldDefinition.ValidationRule> rules = descriptors.stream()
                    .map(this::validationRule)
                    .sorted(Comparator.comparing(DictionaryFieldDefinition.ValidationRule::name))
                    .toList();
            // Ограничения элементов Set/List (например, Set<@NotBlank String>)
            // передаются фронтенду отдельно от ограничений самой коллекции.
            List<DictionaryFieldDefinition.ValidationRule> elementRules = propertyDescriptor == null
                    ? List.of()
                    : propertyDescriptor.getConstrainedContainerElementTypes().stream()
                    .flatMap(element -> element.getConstraintDescriptors().stream())
                    .map(this::validationRule)
                    .sorted(Comparator.comparing(DictionaryFieldDefinition.ValidationRule::name))
                    .toList();
            List<DictionaryFieldDefinition> children = (association || embedded)
                    ? describeFields(targetType, valueType) : List.of();
            List<String> enumValues = valueType.isEnum()
                    ? Arrays.stream(valueType.getEnumConstants())
                    .map(item -> ((Enum<?>) item).name()).toList()
                    : List.of();
            String type = collection ? "array" : association ? "reference" : embedded ? "object"
                    : valueType.isEnum() ? "enum" : kind(valueType);
            boolean readOnly = field.readOnly()
                    || (attr instanceof jakarta.persistence.metamodel.SingularAttribute<?, ?> singular
                    && singular.isId() && DictionaryProperties.hasGeneratedId(entityClass, field.name()))
                    || (collection && DictionaryProperties.inverseAssociation(entityClass, field.name()));
            output.add(new DictionaryFieldDefinition(
                    field.name(), field.label().isBlank() ? field.name() : field.label(),
                    field.description(), type, field.order(), readOnly, field.visible(),
                    required, referenceCode, enumValues, children, rules, elementRules));
        }
        output.sort(Comparator.comparingInt(DictionaryFieldDefinition::order)
                .thenComparing(DictionaryFieldDefinition::name));
        return List.copyOf(output);
    }

    /** Представляем Bean Validation как пары «аннотация — параметры». */
    private DictionaryFieldDefinition.ValidationRule validationRule(ConstraintDescriptor<?> descriptor) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        // Не ограничиваемся заранее известным списком @Size/@Min/@Pattern:
        // у пользовательских ограничений тоже могут быть полезные параметры.
        descriptor.getAttributes().forEach((key, value) -> {
            if (key.equals("message") || key.equals("groups") || key.equals("payload")) return;
            Object safe = serializableAttribute(value);
            if (safe != null) attributes.put(key, safe);
        });
        return new DictionaryFieldDefinition.ValidationRule(
                descriptor.getAnnotation().annotationType().getSimpleName(), Map.copyOf(attributes));
    }

    /** Превращает параметры аннотации в значения, пригодные для JSON. */
    private static Object serializableAttribute(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Enum<?> item) return item.name();
        if (value != null && value.getClass().isArray()) {
            int count = java.lang.reflect.Array.getLength(value);
            List<Object> entries = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                Object safe = serializableAttribute(java.lang.reflect.Array.get(value, index));
                if (safe != null) entries.add(safe);
            }
            return List.copyOf(entries);
        }
        return null;
    }

    private static boolean simple(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class
                || Number.class.isAssignableFrom(type) || type == Boolean.class
                || type == java.util.UUID.class || type == Character.class
                || java.time.temporal.TemporalAccessor.class.isAssignableFrom(type);
    }

    private static String kind(Class<?> type) {
        if (type == Boolean.class || type == boolean.class) return "boolean";
        if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != boolean.class
                && type != char.class) return "number";
        if (type == java.time.LocalDate.class) return "date";
        if (java.time.temporal.TemporalAccessor.class.isAssignableFrom(type)) return "datetime";
        return "string";
    }

    private record RegistryEntry(String code, String name, String entityClass, String dtoClass) { }
}
